/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.service.jobs;

import static com.dremio.exec.store.easy.arrow.ArrowFormatPlugin.FOOTER_OFFSET_SIZE;
import static com.dremio.exec.store.easy.arrow.ArrowFormatPlugin.MAGIC_STRING;
import static com.dremio.exec.store.easy.arrow.ArrowFormatPlugin.MAGIC_STRING_LENGTH;
import static com.dremio.service.jobs.RecordBatchHolder.newRecordBatchHolder;
import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.commons.io.IOUtils;

import com.dremio.common.AutoCloseables.RollbackCloseable;
import com.dremio.common.exceptions.UserException;
import com.dremio.exec.cache.VectorAccessibleSerializable;
import com.dremio.exec.enginemanagement.proto.EngineId;
import com.dremio.exec.enginemanagement.proto.EngineManagementProtos;
import com.dremio.exec.enginemanagement.proto.SubEngineId;
import com.dremio.exec.expr.TypeHelper;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.proto.beans.NodeEndpoint;
import com.dremio.exec.proto.beans.Roles;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.record.RecordBatchData;
import com.dremio.exec.record.VectorContainer;
import com.dremio.exec.store.easy.arrow.ArrowFileFooter;
import com.dremio.exec.store.easy.arrow.ArrowFileFormat;
import com.dremio.exec.store.easy.arrow.ArrowFileMetadata;
import com.dremio.exec.store.easy.arrow.ArrowRecordBatchSummary;
import com.dremio.io.FSInputStream;
import com.dremio.io.file.FileAttributes;
import com.dremio.io.file.FileSystem;
import com.dremio.io.file.Path;
import com.google.common.collect.Lists;
import com.google.common.primitives.Longs;

/**
 * Reader which takes a file and reads the record batches.
 */
class ArrowFileReader implements AutoCloseable {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ArrowFileReader.class);

  private final FileSystem dfs;
  private final ArrowFileMetadata metadata;
  private final BufferAllocator allocator;
  private final Path path;

  private FSInputStream inputStream;

  ArrowFileReader(final FileSystem dfs, Path basePath, final ArrowFileMetadata metadata, final BufferAllocator allocator) {
    this.dfs = dfs;
    this.metadata = metadata;
    this.allocator = allocator;
    this.path = basePath.resolve(metadata.getPath());
  }

  private void openFile() throws IOException {
    inputStream = dfs.open(path);

    if (false /* disable this until a PDFS getFileStatus() issue is fixed AssertionUtil.ASSERT_ENABLED */) {
      final FileAttributes fileAttributes = dfs.getFileAttributes(path);
      final long size = fileAttributes.size();

      // Make sure the file size is at least the 2 * (Magic word size) + Footer offset size
      // We write magic word both at the beginning and at the end of the file.
      if (size < 2 * MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE) {
        throw UserException.dataReadError()
            .message("File is too small to be an Arrow format file")
            .addContext("path", path.toString())
            .build(logger);
      }

      inputStream.setPosition(size - (MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE));

      final long footerOffset = readLong(inputStream);

      final byte[] magic = new byte[MAGIC_STRING_LENGTH];
      IOUtils.readFully(inputStream, magic);
      // Make sure magic word matches
      if (!Arrays.equals(magic, MAGIC_STRING.getBytes())) {
        throw UserException.dataReadError()
            .message("Invalid magic word. File is not an Arrow format file")
            .addContext("path", path.toString())
            .build(logger);
      }

      // Make sure the footer offset is valid
      if (footerOffset < MAGIC_STRING_LENGTH || footerOffset >= (size - (MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE))) {
        throw UserException.dataReadError()
            .message("Invalid footer offset")
            .addContext("filePath", path.toString())
            .addContext("invalid footer offset", String.valueOf(footerOffset))
            .build(logger);
      }
    }
  }

  /**
   * Read the record batches containing the rows in given range.
   * @param start Starting record number in file (0 based index)
   * @param limit number of records to read
   * @return
   */
  List<RecordBatchHolder> read(final long start, final long limit) throws IOException {
    // Make sure the range is valid according to the metadata in footer
    checkArgument(start == 0 && metadata.getRecordCount() == 0|| start >= 0 && start < metadata.getRecordCount(),
        "Invalid start index (%s). Record count in file (%s)", start, metadata.getRecordCount());
    checkArgument(start + limit <= metadata.getRecordCount(),
        "Invalid start index (%s) and limit (%s) combination. Record count in file (%s)",
        start, limit, metadata.getRecordCount());

    openFile();

    final VectorAccessibleSerializable vectorAccessibleSerializable = new VectorAccessibleSerializable(allocator);
    final List<RecordBatchHolder> batches = Lists.newArrayList();
    final ArrowFileFooter footer = metadata.getFooter();

    long runningCount = 0;
    long remaining = limit;
    final int numBatches = footer.getBatchList() == null ? 0 : footer.getBatchList().size();
    for(int batchIndex = 0; batchIndex < numBatches; batchIndex++) {
      ArrowRecordBatchSummary batchSummary = footer.getBatchList().get(batchIndex);
      // Skip past empty batches
      if (batchSummary.getRecordCount() == 0) {
        continue;
      }

      runningCount += batchSummary.getRecordCount();

      // Skip batches until we reach a batch that contains the start index
      if (start >= runningCount) {
        // valid indices in up until the current batch are in range [0, runningCount - 1]
        continue;
      }

      final long currentBatchCount = batchSummary.getRecordCount();

      // Seek to the place where the batch starts and read
      inputStream.setPosition(batchSummary.getOffset());
      vectorAccessibleSerializable.readFromStream(inputStream);
      final VectorContainer vectorContainer = vectorAccessibleSerializable.get();

      // Find the start and end indices within the batch.
      final int batchStart = Math.max(0, (int) (start - (runningCount - currentBatchCount)));
      final int batchEnd = (int) Math.min(currentBatchCount, batchStart + remaining);

      final RecordBatchHolder batchHolder = newRecordBatchHolder(
          new RecordBatchData(vectorContainer, allocator),
          batchStart,
          batchEnd
      );

      batches.add(batchHolder);

      remaining -= batchHolder.size();

      if (remaining == 0) {
        break;
      }
    }

    if (batches.isEmpty()) {
      batches.add(getEmptyBatch());
    }

    return batches;
  }

  @Override
  public void close() throws IOException {
    if (inputStream != null) {
      inputStream.close();
      inputStream = null;
    }
  }

  /**
   * Helper method that creates an empty batch from schema in Arrow footer.
   * @return
   * @throws IOException
   */
  private RecordBatchHolder getEmptyBatch() throws IOException {
    final FileAttributes fileAttributes = dfs.getFileAttributes(path);
    final long size = fileAttributes.size();
    inputStream.setPosition(size - (MAGIC_STRING_LENGTH + FOOTER_OFFSET_SIZE));

    final long footerOffset = readLong(inputStream);

    // Read the footer
    inputStream.setPosition(footerOffset);
    ArrowFileFormat.ArrowFileFooter footer = ArrowFileFormat.ArrowFileFooter.parseDelimitedFrom(inputStream);
    BatchSchema footerSchema = BatchSchema.newBuilder().addSerializedFields(footer.getFieldList()).build();

    final VectorContainer vectorContainer = new VectorContainer();
    try (RollbackCloseable rollback = new RollbackCloseable()) {
      rollback.add(vectorContainer);
      for(Field field : footerSchema) {
        vectorContainer.add(TypeHelper.getNewVector(field, allocator));
      }
      rollback.commit();
    } catch (Exception e) {
      throw new IOException(e);
    }

    vectorContainer.setRecordCount(0);
    vectorContainer.buildSchema();

    return newRecordBatchHolder(new RecordBatchData(vectorContainer, allocator), 0, 0);
  }

  private static Roles toBean(CoordinationProtos.Roles roles) {
    Roles beanRoles = new Roles();
    beanRoles.setDistributedCache(roles.getDistributedCache());
    beanRoles.setJavaExecutor(roles.getJavaExecutor());
    beanRoles.setLogicalPlan(roles.getLogicalPlan());
    beanRoles.setMaster(roles.getMaster());
    beanRoles.setSqlQuery(roles.getSqlQuery());
    beanRoles.setPhysicalPlan(roles.getPhysicalPlan());
    return beanRoles;
  }

  private static EngineId toBean(EngineManagementProtos.EngineId engineId) {
    EngineId beanEngineId = new EngineId();
    beanEngineId.setId(engineId.getId());
    return beanEngineId;
  }

  private static SubEngineId toBean(EngineManagementProtos.SubEngineId subEngineId) {
    SubEngineId beanSubEngineId = new SubEngineId();
    beanSubEngineId.setId(subEngineId.getId());
    return beanSubEngineId;
  }

  static NodeEndpoint toBean(CoordinationProtos.NodeEndpoint nodeEndpoint) {
    NodeEndpoint beanNodeEndpoint = new NodeEndpoint();

    beanNodeEndpoint.setAddress(nodeEndpoint.getAddress());
    beanNodeEndpoint.setEngineId(toBean(nodeEndpoint.getEngineId()));
    beanNodeEndpoint.setSubEngineId(toBean(nodeEndpoint.getSubEngineId()));
    beanNodeEndpoint.setRoles(toBean(nodeEndpoint.getRoles()));
    beanNodeEndpoint.setAvailableCores((nodeEndpoint.getAvailableCores()));
    beanNodeEndpoint.setFabricPort(nodeEndpoint.getFabricPort());
    beanNodeEndpoint.setMaxDirectMemory(nodeEndpoint.getMaxDirectMemory());
    beanNodeEndpoint.setNodeTag(nodeEndpoint.getNodeTag());
    beanNodeEndpoint.setProvisionId(nodeEndpoint.getProvisionId());
    beanNodeEndpoint.setStartTime(nodeEndpoint.getStartTime());
    beanNodeEndpoint.setUserPort(nodeEndpoint.getUserPort());

    return beanNodeEndpoint;
  }

  /**
   * Helper method to convert the protobuf message into a bean class. Currently it ignores converting the row type.
   * Add the support for row type conversion in future if needed.
   *
   * TODO: We need check if there is a way to auto convert protobuf messages to protostuff bean classes.
   *
   * @param metadata
   * @return
   */
  static ArrowFileMetadata toBean(ArrowFileFormat.ArrowFileMetadata metadata) {
    final ArrowFileMetadata beanMetadata = new ArrowFileMetadata();

    beanMetadata.setPath(metadata.getPath());
    beanMetadata.setRecordCount(metadata.getRecordCount());
    beanMetadata.setScreenNodeEndpoint(toBean(metadata.getScreenNodeEndpoint()));

    ArrowFileFooter beanFooter = new ArrowFileFooter();
    beanFooter.setBatchList(new ArrayList<>());
    for(ArrowFileFormat.ArrowRecordBatchSummary summary : metadata.getFooter().getBatchList()) {
      ArrowRecordBatchSummary beanSummary = new ArrowRecordBatchSummary();
      beanSummary.setOffset(summary.getOffset());
      beanSummary.setRecordCount(summary.getRecordCount());

      beanFooter.getBatchList().add(beanSummary);
    }

    beanMetadata.setFooter(beanFooter);

    return beanMetadata;
  }

  private static final ThreadLocal<byte[]> READ_BUFFER = ThreadLocal.withInitial(() -> new byte[Long.BYTES]);

  private static long readLong(InputStream is) throws IOException {
    byte[] buffer = READ_BUFFER.get();
    IOUtils.readFully(is, buffer);

    return Longs.fromByteArray(buffer);
  }
}
