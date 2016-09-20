/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalDirAllocator;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.SequenceFile.CompressionType;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.DefaultCodec;
import org.apache.hadoop.io.serializer.Deserializer;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.apache.hadoop.mapred.IFile.Writer;
import org.apache.hadoop.mapred.Merger.Segment;
import org.apache.hadoop.mapred.SortedRanges.SkipRangeIterator;
import org.apache.hadoop.mapreduce.MRConfig;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.MapContext;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskCounter;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.map.WrappedMapper;
import org.apache.hadoop.mapreduce.security.SecureShuffleUtils;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitIndex;
import org.apache.hadoop.mapreduce.task.MapContextImpl;
import org.apache.hadoop.mapreduce.task.TaskInputOutputContextImpl;
import org.apache.hadoop.pse.PSELineRecordReader;
import org.apache.hadoop.pse.PSERecordReader;
import org.apache.hadoop.util.BytesUtil;
import org.apache.hadoop.util.IndexedSortable;
import org.apache.hadoop.util.IndexedSorter;
import org.apache.hadoop.util.Progress;
import org.apache.hadoop.util.QuickSort;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.zookeeper.ZkConnectException;
import org.apache.hadoop.util.zookeeper.ZkUtil;
import org.apache.hadoop.util.zookeeper.ZooKeeperItf;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event;

//MTP-fix
import org.apache.hadoop.mapred.TaskTracker;
import org.apache.hadoop.mapred.JobTracker;

/** A Map task. */
class MapTask extends Task {
	/**
	 * The size of each record in the index file for the map-outputs.
	 */
	public static final int MAP_OUTPUT_INDEX_RECORD_LENGTH = 24;

	private TaskSplitIndex splitMetaInfo = new TaskSplitIndex();
	private String splitClass;
	private final static int APPROX_HEADER_LENGTH = 150;

	private static final Log LOG = LogFactory.getLog(MapTask.class.getName());

	private Progress mapPhase;
	private Progress sortPhase;

	//MTP-fix
	boolean notCheckpointConfidence = false;
	boolean isSpeculativeMapTask = false;
	String httpAddr = null;

	{ // set phase for this task
		setPhase(TaskStatus.Phase.MAP);
		getProgress().setStatus("map");
	}

	public MapTask() {
		super();
	}

	public MapTask(String jobFile, TaskAttemptID taskId, int partition, TaskSplitIndex splitIndex,
			int numSlotsRequired) {
		super(jobFile, taskId, partition, numSlotsRequired);
		this.splitMetaInfo = splitIndex;
	}

	@Override
	public boolean isMapTask() {
		return true;
	}

	@Override
	public void localizeConfiguration(JobConf conf) throws IOException {
		super.localizeConfiguration(conf);
		// split.dta/split.info files are used only by IsolationRunner.
		// Write the split file to the local disk if it is a normal map task
		// (not a
		// job-setup or a job-cleanup task) and if the user wishes to run
		// IsolationRunner either by setting keep.failed.tasks.files to true or
		// by
		// using keep.tasks.files.pattern
		if (supportIsolationRunner(conf) && isMapOrReduce()) {
			// localize the split meta-information
			Path localSplitMeta = new LocalDirAllocator(MRConfig.LOCAL_DIR).getLocalPathForWrite(
					TaskTracker.getLocalSplitMetaFile(conf.getUser(), getJobID().toString(), getTaskID().toString()),
					conf);
			LOG.debug("Writing local split to " + localSplitMeta);
			DataOutputStream out = FileSystem.getLocal(conf).create(localSplitMeta);
			splitMetaInfo.write(out);
			out.close();
		}
	}

	@Override
	public TaskRunner createRunner(TaskTracker tracker, TaskTracker.TaskInProgress tip) {
		return new MapTaskRunner(tip, tracker, this.conf);
	}

	@Override
	public void write(DataOutput out) throws IOException {
		super.write(out);
		if (isMapOrReduce()) {
			splitMetaInfo.write(out);
			splitMetaInfo = null;
		}
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		super.readFields(in);
		if (isMapOrReduce()) {
			splitMetaInfo.readFields(in);
		}
	}

	// FIXME: sure that not to checkpoint
	public synchronized void disableCheckpoint() {
		notCheckpointConfidence = true;
	}

	/**
	 * This class wraps the user's record reader to update the counters and
	 * progress as records are read.
	 * 
	 * @param <K>
	 * @param <V>
	 */
	class TrackedRecordReader<K, V> implements RecordReader<K, V> {
		private RecordReader<K, V> rawIn;
		private Counters.Counter inputByteCounter;
		private Counters.Counter inputRecordCounter;
		private TaskReporter reporter;
		private long beforePos = -1;
		private long afterPos = -1;

		TrackedRecordReader(RecordReader<K, V> raw, TaskReporter reporter) throws IOException {
			rawIn = raw;
			inputRecordCounter = reporter.getCounter(TaskCounter.MAP_INPUT_RECORDS);
			inputByteCounter = reporter.getCounter(FileInputFormat.COUNTER_GROUP, FileInputFormat.BYTES_READ);
			this.reporter = reporter;
		}

		public K createKey() {
			return rawIn.createKey();
		}

		public V createValue() {
			return rawIn.createValue();
		}

		public synchronized boolean next(K key, V value) throws IOException {
			boolean ret = moveToNext(key, value);
			if (ret) {
				incrCounters();
			}
			return ret;
		}

		protected void incrCounters() {
			inputRecordCounter.increment(1);
			inputByteCounter.increment(afterPos - beforePos);
		}

		protected synchronized boolean moveToNext(K key, V value) throws IOException {
			beforePos = getPos();
			boolean ret = rawIn.next(key, value);
			afterPos = getPos();
			reporter.setProgress(getProgress());
			return ret;
		}

		public long getPos() throws IOException {
			return rawIn.getPos();
		}

		public void close() throws IOException {
			rawIn.close();
		}

		public float getProgress() throws IOException {
			return rawIn.getProgress();
		}

		TaskReporter getTaskReporter() {
			return reporter;
		}
	}

	/**
	 * This class skips the records based on the failed ranges from previous
	 * attempts.
	 */
	class SkippingRecordReader<K, V> extends TrackedRecordReader<K, V> {
		private SkipRangeIterator skipIt;
		private SequenceFile.Writer skipWriter;
		private boolean toWriteSkipRecs;
		private TaskUmbilicalProtocol umbilical;
		private Counters.Counter skipRecCounter;
		private long recIndex = -1;

		SkippingRecordReader(RecordReader<K, V> raw, TaskUmbilicalProtocol umbilical, TaskReporter reporter)
				throws IOException {
			super(raw, reporter);
			this.umbilical = umbilical;
			this.skipRecCounter = reporter.getCounter(TaskCounter.MAP_SKIPPED_RECORDS);
			this.toWriteSkipRecs = toWriteSkipRecs() && SkipBadRecords.getSkipOutputPath(conf) != null;
			skipIt = getSkipRanges().skipRangeIterator();
		}

		public synchronized boolean next(K key, V value) throws IOException {
			if (!skipIt.hasNext()) {
				LOG.warn("Further records got skipped.");
				return false;
			}
			boolean ret = moveToNext(key, value);
			long nextRecIndex = skipIt.next();
			long skip = 0;
			while (recIndex < nextRecIndex && ret) {
				if (toWriteSkipRecs) {
					writeSkippedRec(key, value);
				}
				ret = moveToNext(key, value);
				skip++;
			}
			// close the skip writer once all the ranges are skipped
			if (skip > 0 && skipIt.skippedAllRanges() && skipWriter != null) {
				skipWriter.close();
			}
			skipRecCounter.increment(skip);
			reportNextRecordRange(umbilical, recIndex);
			if (ret) {
				incrCounters();
			}
			return ret;
		}

		protected synchronized boolean moveToNext(K key, V value) throws IOException {
			recIndex++;
			return super.moveToNext(key, value);
		}

		@SuppressWarnings("unchecked")
		private void writeSkippedRec(K key, V value) throws IOException {
			if (skipWriter == null) {
				Path skipDir = SkipBadRecords.getSkipOutputPath(conf);
				Path skipFile = new Path(skipDir, getTaskID().toString());
				skipWriter = SequenceFile.createWriter(skipFile.getFileSystem(conf), conf, skipFile,
						(Class<K>) createKey().getClass(), (Class<V>) createValue().getClass(), CompressionType.BLOCK,
						getTaskReporter());
			}
			skipWriter.append(key, value);
		}
	}

	//MTP-fix
	public void setSpeculative(boolean flag){
		isSpeculativeMapTask = flag;
	}

	//MTP-fix
	public void setHttpAddr(String addr){
		httpAddr = addr;
	}

	public String getHttpAddr(){
		return httpAddr;
	}
	
	@Override
	public void run(final JobConf job, final TaskUmbilicalProtocol umbilical)
			throws IOException, ClassNotFoundException, InterruptedException {
		this.umbilical = umbilical;

		if (isMapTask()) {
			mapPhase = getProgress().addPhase("map", 0.667f);
			sortPhase = getProgress().addPhase("sort", 0.333f);

		}
		TaskReporter reporter = startReporter(umbilical);

		boolean useNewApi = job.getUseNewMapper();
		initialize(job, getJobID(), reporter, useNewApi);
		// For PSE. So far, Child task know whether the task is speculative and
		// what's http service address it is
		// FIXME the format of httpAddress is only suitable for MapTask
		setSpeculative(umbilical.isSpeculative(getTaskID()));
		setHttpAddr(umbilical.getTrackerAddress());

		//MTP-fix
		//LOG.info("WYG: in MapTask.run(), after initialize(), this task is speculative? : " + isSpeculative());
		LOG.info("WYG: in MapTask.run(), after initialize(), this task is speculative? : " + umbilical.isSpeculative(getTaskID()));
		LOG.info("WYG: task is runned on TaskTracker : " + getHttpAddr());

		// check if it is a cleanupJobTask
		if (jobCleanup) {
			runJobCleanupTask(umbilical, reporter);
			return;
		}
		if (jobSetup) {
			runJobSetupTask(umbilical, reporter);
			return;
		}
		if (taskCleanup) {
			runTaskCleanupTask(umbilical, reporter);
			return;
		}

		if (useNewApi) {
			runNewMapper(job, splitMetaInfo, umbilical, reporter);
		} else {
			runOldMapper(job, splitMetaInfo, umbilical, reporter);
		}
		done(umbilical, reporter);
	}

	@SuppressWarnings("unchecked")
	private <T> T getSplitDetails(Path file, long offset) throws IOException {
		FileSystem fs = file.getFileSystem(conf);
		FSDataInputStream inFile = fs.open(file);
		inFile.seek(offset);
		String className = Text.readString(inFile);
		Class<T> cls;
		try {
			cls = (Class<T>) conf.getClassByName(className);
		} catch (ClassNotFoundException ce) {
			IOException wrap = new IOException("Split class " + className + " not found");
			wrap.initCause(ce);
			throw wrap;
		}
		SerializationFactory factory = new SerializationFactory(conf);
		Deserializer<T> deserializer = (Deserializer<T>) factory.getDeserializer(cls);
		deserializer.open(inFile);
		T split = deserializer.deserialize(null);
		long pos = inFile.getPos();
		getCounters().findCounter(TaskCounter.SPLIT_RAW_BYTES).increment(pos - offset);
		inFile.close();
		return split;
	}

	@SuppressWarnings("unchecked")
	private <INKEY, INVALUE, OUTKEY, OUTVALUE> void runOldMapper(final JobConf job, final TaskSplitIndex splitIndex,
			final TaskUmbilicalProtocol umbilical, TaskReporter reporter)
			throws IOException, InterruptedException, ClassNotFoundException {
		InputSplit inputSplit = getSplitDetails(new Path(splitIndex.getSplitLocation()), splitIndex.getStartOffset());

		updateJobWithSplit(job, inputSplit);
		reporter.setInputSplit(inputSplit);

		RecordReader<INKEY, INVALUE> rawIn = // open input
				job.getInputFormat().getRecordReader(inputSplit, job, reporter);
		RecordReader<INKEY, INVALUE> in = isSkipping()
				? new SkippingRecordReader<INKEY, INVALUE>(rawIn, umbilical, reporter)
				: new TrackedRecordReader<INKEY, INVALUE>(rawIn, reporter);
		job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());

		int numReduceTasks = conf.getNumReduceTasks();
		LOG.info("numReduceTasks: " + numReduceTasks);
		MapOutputCollector collector = null;
		if (numReduceTasks > 0) {
			collector = new MapOutputBuffer(umbilical, job, reporter);
		} else {
			collector = new DirectMapOutputCollector(umbilical, job, reporter);
		}
		MapRunnable<INKEY, INVALUE, OUTKEY, OUTVALUE> runner = ReflectionUtils.newInstance(job.getMapRunnerClass(),
				job);

		try {
			runner.run(in, new OldOutputCollector(collector, conf), reporter);
			mapPhase.complete();
			setPhase(TaskStatus.Phase.SORT);
			statusUpdate(umbilical);
			collector.flush();
		} finally {
			// close
			in.close(); // close input
			collector.close();
		}
	}

	/**
	 * Update the job with details about the file split
	 * 
	 * @param job
	 *            the job configuration to update
	 * @param inputSplit
	 *            the file split
	 */
	private void updateJobWithSplit(final JobConf job, InputSplit inputSplit) {
		if (inputSplit instanceof FileSplit) {
			FileSplit fileSplit = (FileSplit) inputSplit;
			job.set(JobContext.MAP_INPUT_FILE, fileSplit.getPath().toString());
			job.setLong(JobContext.MAP_INPUT_START, fileSplit.getStart());
			job.setLong(JobContext.MAP_INPUT_PATH, fileSplit.getLength());
		}
	}

	static class NewTrackingRecordReader<K, V> extends org.apache.hadoop.mapreduce.RecordReader<K, V> {
		private final org.apache.hadoop.mapreduce.RecordReader<K, V> real;
		private final org.apache.hadoop.mapreduce.Counter inputRecordCounter;
		private final TaskReporter reporter;

		private float lastProgress = 0.0f;

		NewTrackingRecordReader(org.apache.hadoop.mapreduce.RecordReader<K, V> real, TaskReporter reporter) {
			this.real = real;
			this.reporter = reporter;
			this.inputRecordCounter = reporter.getCounter(TaskCounter.MAP_INPUT_RECORDS);
		}

		@Override
		public void close() throws IOException {
			real.close();
		}

		@Override
		public K getCurrentKey() throws IOException, InterruptedException {
			return real.getCurrentKey();
		}

		@Override
		public V getCurrentValue() throws IOException, InterruptedException {
			return real.getCurrentValue();
		}

		@Override
		public float getProgress() throws IOException, InterruptedException {
			return real.getProgress();
		}

		@Override
		public void initialize(org.apache.hadoop.mapreduce.InputSplit split,
				org.apache.hadoop.mapreduce.TaskAttemptContext context) throws IOException, InterruptedException {
			real.initialize(split, context);
		}

		@Override
		public boolean nextKeyValue() throws IOException, InterruptedException {
			boolean result = real.nextKeyValue();
			if (result) {
				inputRecordCounter.increment(1);
			}
			// TODO, just for measure the SE task's final progress, so LOG it
			float p = getProgress();
			reporter.setProgress(p);

			if (p - lastProgress > 0.005) {
				LOG.info("WYG-ProgressTracking: " + p);
				lastProgress = p;
			}

			return result;
		}
	}

	// FIXME: PSE road --> NewTrackingPSELineRecordReader, for checkpoint
	static class NewTrackingPSELineRecordReader<K, V> extends PSERecordReader<K, V> {
		private final PSELineRecordReader real;
		private final org.apache.hadoop.mapreduce.Counter inputRecordCounter;
		private final TaskReporter reporter;

		// FIXME: maintain checkpoint info
		private boolean needCheckpoint = false;

		NewTrackingPSELineRecordReader(org.apache.hadoop.mapreduce.RecordReader<K, V> real, TaskReporter reporter) {
			this.real = (PSELineRecordReader) real;
			this.reporter = reporter;
			this.inputRecordCounter = reporter.getCounter(TaskCounter.MAP_INPUT_RECORDS);
		}

		@Override
		public void close() throws IOException {
			real.close();
		}

		@Override
		public K getCurrentKey() throws IOException, InterruptedException {
			return (K) real.getCurrentKey();
		}

		@Override
		public V getCurrentValue() throws IOException, InterruptedException {
			return (V) real.getCurrentValue();
		}

		@Override
		public float getProgress() throws IOException, InterruptedException {
			return real.getProgress();
		}

		@Override
		public void initialize(org.apache.hadoop.mapreduce.InputSplit split,
				org.apache.hadoop.mapreduce.TaskAttemptContext context) throws IOException, InterruptedException {
			real.initialize(split, context);
		}

		@Override
		public void initialize(org.apache.hadoop.mapreduce.InputSplit split, long position, TaskAttemptContext context)
				throws IOException {
			real.initialize(split, position, context);
		}

		@Override
		public boolean nextKeyValue() throws IOException, InterruptedException {
			boolean result = real.nextKeyValue();
			if (result) {
				inputRecordCounter.increment(1);
			}

			boolean ckpt = reporter.setProgressPSE(getProgress());
			// FIXME try to see if it needs checkpoint
			if (!needCheckpoint && ckpt) {
				needCheckpoint = true;
				LOG.info("WYG: Before reading newKeyValue, NewTrackingPSELineRecordReader get first <" + ckpt
						+ "> checkpoint action from reporter.");
			}

			//MTP-fix
			/*reporter.setProgress(getProgress());
			// FIXME try to see if it needs checkpoint
			if (!needCheckpoint) {
				needCheckpoint = true;
			}*/

			return result;
		}

		public synchronized long getPos() throws IOException {
			return real.getPos();
		}

		public boolean shouldChckpoint() {
			return needCheckpoint;
		}

	}

	/**
	 * Since the mapred and mapreduce Partitioners don't share a common
	 * interface (JobConfigurable is deprecated and a subtype of
	 * mapred.Partitioner), the partitioner lives in Old/NewOutputCollector.
	 * Note that, for map-only jobs, the configured partitioner should not be
	 * called. It's common for partitioners to compute a result mod numReduces,
	 * which causes a div0 error
	 */
	private static class OldOutputCollector<K, V> implements OutputCollector<K, V> {
		private final Partitioner<K, V> partitioner;
		private final MapOutputCollector<K, V> collector;
		private final int numPartitions;

		@SuppressWarnings("unchecked")
		OldOutputCollector(MapOutputCollector<K, V> collector, JobConf conf) {
			numPartitions = conf.getNumReduceTasks();
			if (numPartitions > 1) {
				partitioner = (Partitioner<K, V>) ReflectionUtils.newInstance(conf.getPartitionerClass(), conf);
			} else {
				partitioner = new Partitioner<K, V>() {
					@Override
					public void configure(JobConf job) {
					}

					@Override
					public int getPartition(K key, V value, int numPartitions) {
						return numPartitions - 1;
					}
				};
			}
			this.collector = collector;
		}

		@Override
		public void collect(K key, V value) throws IOException {
			try {
				collector.collect(key, value, partitioner.getPartition(key, value, numPartitions));
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupt exception", ie);
			}
		}
	}

	private class NewDirectOutputCollector<K, V> extends org.apache.hadoop.mapreduce.RecordWriter<K, V> {
		private final org.apache.hadoop.mapreduce.RecordWriter out;

		private final TaskReporter reporter;

		private final Counters.Counter mapOutputRecordCounter;

		@SuppressWarnings("unchecked")
		NewDirectOutputCollector(MRJobConfig jobContext, JobConf job, TaskUmbilicalProtocol umbilical,
				TaskReporter reporter) throws IOException, ClassNotFoundException, InterruptedException {
			this.reporter = reporter;
			out = outputFormat.getRecordWriter(taskContext);
			mapOutputRecordCounter = reporter.getCounter(TaskCounter.MAP_OUTPUT_RECORDS);
		}

		@Override
		@SuppressWarnings("unchecked")
		public void write(K key, V value) throws IOException, InterruptedException {
			reporter.progress();
			out.write(key, value);
			mapOutputRecordCounter.increment(1);
		}

		@Override
		public void close(TaskAttemptContext context) throws IOException, InterruptedException {
			reporter.progress();
			if (out != null) {
				out.close(context);
			}
		}

		//MTP-fix
		//@Override
		public byte[] checkpoint() {
			// TODO Auto-generated method stub
			return "[NewDirectOutputCollector.checkpoint()],TODO..........".getBytes();
		}

		//MTP-fix
		//@Override
		public void initialize(byte[] checkpoint) throws IOException {
			// TODO Auto-generated method stub
			throw new IOException("NewDirectOutputCollector.initialize(byte[] checkpoint) needs to be implmented!!!");
		}
	}

	private class NewOutputCollector<K, V> extends org.apache.hadoop.mapreduce.RecordWriter<K, V> {
		private final MapOutputCollector<K, V> collector;
		private final org.apache.hadoop.mapreduce.Partitioner<K, V> partitioner;
		private final int partitions;

		@SuppressWarnings("unchecked")
		NewOutputCollector(org.apache.hadoop.mapreduce.JobContext jobContext, JobConf job,
				TaskUmbilicalProtocol umbilical, TaskReporter reporter) throws IOException, ClassNotFoundException {
			collector = new MapOutputBuffer<K, V>(umbilical, job, reporter);
			partitions = jobContext.getNumReduceTasks();
			if (partitions > 1) {
				partitioner = (org.apache.hadoop.mapreduce.Partitioner<K, V>) ReflectionUtils
						.newInstance(jobContext.getPartitionerClass(), job);
			} else {
				partitioner = new org.apache.hadoop.mapreduce.Partitioner<K, V>() {
					@Override
					public int getPartition(K key, V value, int numPartitions) {
						return partitions - 1;
					}
				};
			}
		}

		@Override
		public void write(K key, V value) throws IOException, InterruptedException {
			collector.collect(key, value, partitioner.getPartition(key, value, partitions));
		}

		@Override
		public void close(TaskAttemptContext context) throws IOException, InterruptedException {
			try {
				collector.flush();
			} catch (ClassNotFoundException cnf) {
				throw new IOException("can't find class ", cnf);
			}
			collector.close();
		}

		//MTP-fix
		//@Override
		public byte[] checkpoint() throws IOException {
			/*
			 * String checkpointStr = "[NewOutputCollector.checkpoint()]: " +
			 * collector.doCheckpoint().toString(); return
			 * checkpointStr.getBytes();
			 */
			return collector.doCheckpoint().toBytes();
		}

		//MTP-fix
		//@Override
		public void initialize(byte[] checkpoint) throws IOException {
			// TODO Auto-generated method stub
			MapOutputCheckpoint mapOutputCheckpoint = new MapOutputCheckpoint();
			mapOutputCheckpoint.fromBytes(checkpoint);
			collector.initFromCheckpoint(mapOutputCheckpoint);
		}

	}

	/**
	 * The context that is given to the {@link Mapper}.
	 * 
	 * @param <KEYIN>
	 *            the key input type to the Mapper
	 * @param <VALUEIN>
	 *            the value input type to the Mapper
	 * @param <KEYOUT>
	 *            the key output type from the Mapper
	 * @param <VALUEOUT>
	 *            the value output type from the Mapper
	 */
	public class PSEMapContextImpl<KEYIN, VALUEIN, KEYOUT, VALUEOUT>
			extends TaskInputOutputContextImpl<KEYIN, VALUEIN, KEYOUT, VALUEOUT>
			implements MapContext<KEYIN, VALUEIN, KEYOUT, VALUEOUT> {
		private NewTrackingPSELineRecordReader reader;
		private org.apache.hadoop.mapreduce.InputSplit split;

		private boolean needCheckpoint = false;
		/*MTP-fix*/
		private org.apache.hadoop.mapreduce.RecordWriter<KEYOUT,VALUEOUT> output = null;

		public PSEMapContextImpl(Configuration conf, TaskAttemptID taskid,
				org.apache.hadoop.mapreduce.RecordReader<KEYIN, VALUEIN> reader,
				org.apache.hadoop.mapreduce.RecordWriter<KEYOUT, VALUEOUT> writer,
				org.apache.hadoop.mapreduce.OutputCommitter committer,
				org.apache.hadoop.mapreduce.StatusReporter reporter, org.apache.hadoop.mapreduce.InputSplit split) {
			super(conf, taskid, writer, committer, reporter);
			this.reader = (NewTrackingPSELineRecordReader) reader;
			this.split = split;
			/*MTP-fix*/
			this.output = writer;
		}

		/**
		 * Get the input split for this map.
		 */
		public org.apache.hadoop.mapreduce.InputSplit getInputSplit() {
			return split;
		}

		@Override
		public KEYIN getCurrentKey() throws IOException, InterruptedException {
			return (KEYIN) reader.getCurrentKey();
		}

		@Override
		public VALUEIN getCurrentValue() throws IOException, InterruptedException {
			return (VALUEIN) reader.getCurrentValue();
		}

		@Override
		public boolean nextKeyValue() throws IOException, InterruptedException {
			if (!needCheckpoint && reader.shouldChckpoint()) {
				needCheckpoint = true;
				doCheckpoint();
			}
			return reader.nextKeyValue();
		}

		public void doCheckpoint() throws IOException {
			String zkConnectStr = conf.get(MRJobConfig.TASK_ZOOKEEPER_ADDRESS);
			int timeout = conf.getInt(MRJobConfig.TASK_ZOOKEEPER_TIMEOUT, 6000);
			String parentZNode = "/pse/" + getJobID() + "/" + getTaskID().getTaskID();

			LOG.info("WYG: zkConnectStr=" + zkConnectStr + ", zkTimeout=" + timeout + ", parentZNode=" + parentZNode
					+ ".");
			ZooKeeperItf zk = null;
			try {
				zk = ZkUtil.connect(zkConnectStr, timeout);
			} catch (ZkConnectException e) {
				LOG.warn("WYG: Cannot connect to ZooKeeper, so do nothing but return.");
				return;
			}

			if (!preCheckpoint(zk, parentZNode, /* true */false)) {
				LOG.info(
						"WYG: No need to do checkpoint, possibly SE task has not been launched, or waited for a long time and started to wholly SE.");
				return;
			}
			/**
			 * After preCheckpoint(), make sure: 1. Two znodes existed, 2.
			 * 'status' znode had been in 'checkpointing' state
			 */

			/**
			 * do checkpoint --> I. MapInputCheckpoint; II. MapOutputCheckpoint
			 */
			// I. MapInputCheckpoint
			long inCkpt = reader.getPos();
			LOG.info("WYG: doCheckpoint()--I. MapInputCheckpoint = " + inCkpt);
			LOG.debug("WYG: writer is " + (output instanceof NewDirectOutputCollector
					? NewDirectOutputCollector.class.getName() : NewOutputCollector.class.getName()));

			// II. MapOutputCheckpoint
			//MTP-FIX
			//byte[] outCkpt = output.checkpoint();
			byte[] outCkpt = output instanceof NewDirectOutputCollector ? ((NewDirectOutputCollector) output).checkpoint() : ((NewOutputCollector) output).checkpoint();//not implemented
			String outCkptStr = (null != outCkpt) ? new String(outCkpt) : "[null]";
			LOG.info("WYG: doCheckpoint()--II. MapOutputCheckpoint = " + outCkptStr);

			boolean succ = postCheckpoint(zk, parentZNode, inCkpt, outCkpt);
			LOG.info("WYG: doCkeckpoint() has " + (succ == true ? "successfully" : "un-successfully(maybe no need)")
					+ " written checkpoint data to zk.");

			// TODO , for test PSE task
			long delay = conf.getLong("pse.delay.after.checkpoint", 0);
			LOG.info("WYG: After original task's checkpoint, delay " + delay + " ms for SE task for Test!!!!");
			try {
				Thread.sleep(delay);
			} catch (InterruptedException e) {
				// ignore
			}
			LOG.info("WYG: Finished delaying " + delay + " ms for SE task for Test!!!!");
		}

		/**
		 * 
		 * @return whether checkpoint or not
		 */
		private boolean preCheckpoint(ZooKeeperItf zk, String parent, boolean simulateSEtask) {
			/**
			 * Normally, the statusNode should be there already (it was created
			 * by SE task). [case 1] However, if the statusNode is not ready,
			 * which means that the SE task has not been scheduled, this
			 * original task should create the znode and set status to
			 * 'checkpointing', return true;
			 */
			String statusNode = parent + "/status";
			String checkpointNode = parent + "/checkpoint";

			try {

				// TODO: this section is for test ---------------------- For
				// test
				// ---------------------------
				if (simulateSEtask) {
					LOG.info("WYG: Begin to create status node for test.");
					if (zk.exists(statusNode, null) == null) {
						ZkUtil.createPath(zk, statusNode, "waiting".getBytes());
					}
					LOG.info("WYG: End to create status node for test.");
				} // TODO: for test -------------------------------------- For
					// test
					// --------------------------

				// case 1:
				if (zk.exists(statusNode, null) == null) {
					ZkUtil.createPath(zk, statusNode, "checkpointing".getBytes());
					if (zk.exists(checkpointNode, null) == null) {
						ZkUtil.createPath(zk, checkpointNode);
					}
					return true;
				}

				// case 2:
				byte[] status = zk.getData(statusNode, null, null);
				String statusStr = null;
				if (status != null) {
					statusStr = new String(status);
					LOG.info("WYG: status = " + statusStr);
				} else {
					// FIXME znode has no data, it has not been ready?
					LOG.info("WYG: status = null. (something is wrong!)");
					return false;
				}
				if (statusStr.equalsIgnoreCase("UnCheckpoint")) {
					// uncheckpoint, since SE task left
					disableCheckpoint();
					return false;
				}

				assert statusStr.equalsIgnoreCase("waiting");
				zk.setData(statusNode, "checkpointing".getBytes(), -1);
				if (zk.exists(checkpointNode, null) == null) {
					ZkUtil.createPath(zk, checkpointNode);
				}
			} catch (KeeperException e) {
				LOG.info("WYG: KeeperException in preCheckpoint(), so return false.", e);
				return false;
			} catch (InterruptedException e) {
				LOG.info("WYG: InterruptedException in preCheckpoint(), so return false.", e);
				return false;
			}
			return true;
		}

		/**
		 * 
		 * @param inCkpt
		 * @param outCkpt
		 * @return whether succeed to write checkpoint into znode
		 */
		private boolean postCheckpoint(ZooKeeperItf zk, String parent, long inCkpt, byte[] outCkpt) {
			// be sure: 1. 'status' znode is in 'checkpointing' state;
			// 2. 'checkpoint' znode exist and data is null.
			// to do: 1. set checkpoint data;
			// 2. change 'status' to 'done'.
			String statusNode = parent + "/status";
			String checkpointNode = parent + "/checkpoint";
			byte[] b = BytesUtil.long2byteArray(inCkpt);
			byte[] ckpt = null;
			if (outCkpt != null) {
				ckpt = new byte[8 + outCkpt.length];
				System.arraycopy(b, 0, ckpt, 0, 8);
				System.arraycopy(outCkpt, 0, ckpt, 8, outCkpt.length);
			} else {
				ckpt = new byte[8];
				System.arraycopy(b, 0, ckpt, 0, 8);
			}
			try {
				zk.setData(checkpointNode, ckpt, -1);
				zk.setData(statusNode, "done".getBytes(), -1);
			} catch (KeeperException e) {
				LOG.info("WYG: KeeperException: ", e);
				return false;
			} catch (InterruptedException e) {
				LOG.info("WYG: InterruptedException: ", e);
				return false;
			}
			return true;
		}

	}

	/*
	 * For PSE. SE task try to go PSE utill timeout
	 */
	private Object pseMapWaitObj = new Object();
	private boolean isWaitTimeout = true;
	private byte[] checkpointData = null;

	@SuppressWarnings("unchecked")
	private <INKEY, INVALUE, OUTKEY, OUTVALUE> void runNewMapper(final JobConf job, final TaskSplitIndex splitIndex,
			final TaskUmbilicalProtocol umbilical, TaskReporter reporter)
			throws IOException, ClassNotFoundException, InterruptedException {
		// make a task context so we can get the classes
		org.apache.hadoop.mapreduce.TaskAttemptContext taskContext = new org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl(
				job, getTaskID());
		// make a mapper
		org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE> mapper = (org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE>) ReflectionUtils
				.newInstance(taskContext.getMapperClass(), job);
		// make the input format
		org.apache.hadoop.mapreduce.InputFormat<INKEY, INVALUE> inputFormat = (org.apache.hadoop.mapreduce.InputFormat<INKEY, INVALUE>) ReflectionUtils
				.newInstance(taskContext.getInputFormatClass(), job);

		// rebuild the input split
		org.apache.hadoop.mapreduce.InputSplit split = null;
		split = getSplitDetails(new Path(splitIndex.getSplitLocation()), splitIndex.getStartOffset());

		org.apache.hadoop.mapreduce.RecordReader<INKEY, INVALUE> input = null;

		boolean usePSE = conf.getBoolean(MRJobConfig.TASK_MAP_USE_PSE, false);
		boolean isFileSplit = false;
		LOG.debug("WYG: usePSE = " + usePSE);
		/************************
		 * PSE or not PSE, depends on FileSplit
		 **************************************************************/
		// TODO: check split type, PSE only process {FileSplit}(default is
		// TextInputFormat and FileSplit)
		if (usePSE && split instanceof org.apache.hadoop.mapreduce.lib.input.FileSplit) {
			LOG.debug(
					"WYG: Passed to FileSplit check, input split of MapTask is [org.apache.hadoop.mapreduce.lib.input.FileSplit].");
			isFileSplit = true;
			input = new NewTrackingPSELineRecordReader<INKEY, INVALUE>(
					inputFormat.createRecordReader(split, taskContext), reporter);
		} else {
			LOG.debug("WYG:  Not PSE! use NewTrackingRecordReader.");
			input = new NewTrackingRecordReader<INKEY, INVALUE>(inputFormat.createRecordReader(split, taskContext),
					reporter);
		}
		/************************
		 * PSE or not PSE, depends on FileSplit
		 **************************************************************/

		org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE>.Context mapperContext = null;
		job.setBoolean(JobContext.SKIP_RECORDS, isSkipping());

		org.apache.hadoop.mapreduce.RecordWriter output = null;
		// get an output object
		if (job.getNumReduceTasks() == 0) {
			output = new NewDirectOutputCollector(taskContext, job, umbilical, reporter);
		} else {
			output = new NewOutputCollector(taskContext, job, umbilical, reporter);
		}
		
		//MTP-fix
		/*int numberOfReduceTasks = job.getNumReduceTasks();
		if (numberOfReduceTasks == 0) {
			output = new NewDirectOutputCollector(taskContext, job, umbilical, reporter);
		} else {
			output = new NewOutputCollector(taskContext, job, umbilical, reporter);
		}*/

		if (usePSE && isFileSplit) {
			LOG.info("WYG: mapper use PSE procedure.");
			org.apache.hadoop.mapreduce.MapContext<INKEY, INVALUE, OUTKEY, OUTVALUE> mapContext = new PSEMapContextImpl<INKEY, INVALUE, OUTKEY, OUTVALUE>(
					job, getTaskID(), input, output, committer, reporter, split);

			mapperContext = new WrappedMapper<INKEY, INVALUE, OUTKEY, OUTVALUE>().getMapContext(mapContext);

			// for PSE: in case of pse task, it should copy data from original
			// task and then start from a checkpoint
			//MTP-fix
			LOG.debug("WYG: Before initializing inputsplit and run, this MapTask is speculative?: " + umbilical.isSpeculative(getTaskID()));
			if (umbilical.isSpeculative(getTaskID())) {
				LOG.debug("WYG: It is speculative, start from a checkpoint.");

				String zkConnectStr = conf.get(MRJobConfig.TASK_ZOOKEEPER_ADDRESS);
				int timeout = conf.getInt(MRJobConfig.TASK_ZOOKEEPER_TIMEOUT, 6000);
				String parentZNode = "/pse/" + getJobID() + "/" + getTaskID().getTaskID();

				LOG.debug("WYG: zkConnectStr=" + zkConnectStr + ", zkTimeout=" + timeout + ", parentZNode="
						+ parentZNode + ".");
				ZooKeeperItf zk = null;
				try {
					zk = ZkUtil.connect(zkConnectStr, timeout);
				} catch (ZkConnectException e) {
					LOG.warn("WYG: Cannot connect to ZooKeeper, so go to WSE!");
					// Go to WSE
					runWSENewMapper(mapperContext, mapper, input, output, split, umbilical);
					return;
				}
				String statusNode = parentZNode + "/status";
				String checkpointNode = parentZNode + "/checkpoint";
				int waitingTime = conf.getInt(MRJobConfig.TASK_PSE_WAITING_TIMEOUT, 15000);

				PSEStatusWatcher pseWatcher = new PSEStatusWatcher(zk, checkpointNode);
				boolean isAreadyDone = false;
				try {
					if (zk.exists(statusNode, pseWatcher) == null) {
						ZkUtil.createPath(zk, statusNode, "waiting".getBytes());
						zk.exists(statusNode, pseWatcher);
					} else {
						// see preCheckpoint in original task's case 1:
						// if went here, the statusNode was created by original
						// task
						// let's see the status, if 'checkpointing', set watcher
						// and go; if 'done', wonderful, it is already!
						byte[] status = zk.getData(statusNode, null, null);
						String statusStr = null;
						if (status != null) {
							statusStr = new String(status);
						}
						if (statusStr.equalsIgnoreCase("done")) {
							isAreadyDone = true;
							// no need to set watcher, fetch checkpoint data
							checkpointData = zk.getData(checkpointNode, false, null);
						} else {
							zk.exists(statusNode, pseWatcher);
						}
					}
				} catch (KeeperException e) {
					// Go to WSE
					runWSENewMapper(mapperContext, mapper, input, output, split, umbilical);
					return;
				}

				if (!isAreadyDone) {
					// the statusNode is 'checkpointing', wait here
					LOG.info("WYG: Waiting for PSE, at most waiting " + (waitingTime / 1000) + " seconds.");
					synchronized (pseMapWaitObj) {
						pseMapWaitObj.wait(waitingTime);
					}
					LOG.info("WYG: Exit from waiting area, and isWaitTimeout = " + isWaitTimeout);

					if (isWaitTimeout || checkpointData == null) {
						LOG.info("WYG: Failed to go PSE, since (isWaitTimeout = " + isWaitTimeout
								+ ") or (checkpointData==null? : " + (checkpointData == null) + "). Go to WSE!!!");
						try {
							zk.setData(statusNode, "UnCheckpoint".getBytes(), -1);
						} catch (KeeperException e) {
							LOG.warn("WYG: Not able to change status node to 'UnCheckpoint' state.");
						}
						// Go to WSE directly, it is safe here since
						// checkpointData has not
						// been copied
						runWSENewMapper(mapperContext, mapper, input, output, split, umbilical);
						return;
					}
				}

				// Start to PSE
				LOG.info("WYG: start to PSE.");
				byte[] inCkpt = new byte[8];
				System.arraycopy(checkpointData, 0, inCkpt, 0, 8);
				long mapInCkpt = BytesUtil.byteArray2long(inCkpt);
				LOG.debug("WYG: Get checkpointData. mapInCkpt = " + mapInCkpt);

				byte[] outCkpt = null;
				MapOutputCheckpoint mapOutCkpt = null;
				if (checkpointData.length > 8) {
					outCkpt = new byte[checkpointData.length - 8];
					System.arraycopy(checkpointData, 8, outCkpt, 0, checkpointData.length - 8);
					mapOutCkpt = new MapOutputCheckpoint();
					mapOutCkpt.fromBytes(outCkpt);
				}
				if (outCkpt == null) {
					LOG.debug("WYG: MapOutputCheckpoint is null, go to WSE!");
					runWSENewMapper(mapperContext, mapper, input, output, split, umbilical);
					return;
				}
				LOG.debug("WYG: Get checkpointData. mapOutCkpt = " + mapOutCkpt == null ? "[null]"
						: mapOutCkpt.toString());

				LOG.debug("WYG: Start to copy checkpoint data via http.");
				long copyStart = System.currentTimeMillis();
				long copiedDataSize = 0L;
				// TODO: (I) copy map output checkpoint
				ZipInputStream httpInput = null;
				// boolean connectSucceeded = false;
				// boolean useZip = true;
				try {
					URL url = getMapCheckpointURL(mapOutCkpt.httpAddress, 50060, getJobID(), mapOutCkpt.taskAttempt,
							mapOutCkpt.startIndex, mapOutCkpt.numSpills);
					URLConnection connection = url.openConnection();
					// generate hash of the url
					// String msgToEncode =
					// SecureShuffleUtils.buildMsgFrom(url);
					// String encHash =
					// SecureShuffleUtils.hashFromString(msgToEncode,
					// getJobTokenSecret());
					//
					// // put url hash into http header
					// connection.addRequestProperty(
					// SecureShuffleUtils.HTTP_HEADER_URL_HASH, encHash);
					// set the read timeout
					connection.setRequestProperty("Connection", "Keep-Alive");
					connection.setRequestProperty("Accept-Encoding", "gzip");

					connection.setReadTimeout(180000);
					connect(connection, 180000);
					// connectSucceeded = true;
					final FileSystem rfs = ((LocalFileSystem) FileSystem.getLocal(job)).getRaw();
					// Note that, calling getInputStream makes to send http
					// request
					httpInput = new ZipInputStream(connection.getInputStream());
					copiedDataSize = copyCheckpointData(httpInput, rfs);
				} catch (IOException ioe) {
					LOG.error("WYG: Failed to copy map output checkpoint.", ioe);
					throw ioe;
				}
				LOG.info("WYG: Finished to copy ckpt files. copying " + copiedDataSize + " bytes, using "
						+ (System.currentTimeMillis() - copyStart) + " ms.");

				// (I) adjust input:
				//MTP-FIX
				//input.initialize(split, mapInCkpt, mapperContext);
				if (input instanceof NewTrackingPSELineRecordReader) {
					((NewTrackingPSELineRecordReader) input).initialize(split, mapInCkpt, mapperContext);
				} else {
					((NewTrackingRecordReader) input).initialize(split, mapperContext);
				}

				// (II) adjust output?? TODO
				//MTP-FIX
				//output.initialize(outCkpt);
				if (output instanceof NewDirectOutputCollector) {
					((NewDirectOutputCollector) output).initialize(outCkpt);
				} else {
					((NewOutputCollector) output).initialize(outCkpt);
				}
				

				mapper.run(mapperContext);
				mapPhase.complete();
				setPhase(TaskStatus.Phase.SORT);
				statusUpdate(umbilical);
				input.close();
				output.close(mapperContext);

				return;
			}
		} else {
			LOG.info("WYG: mapper use traditional procedure.");
			org.apache.hadoop.mapreduce.MapContext<INKEY, INVALUE, OUTKEY, OUTVALUE> mapContext = new MapContextImpl<INKEY, INVALUE, OUTKEY, OUTVALUE>(
					job, getTaskID(), input, output, committer, reporter, split);

			mapperContext = new WrappedMapper<INKEY, INVALUE, OUTKEY, OUTVALUE>().getMapContext(mapContext);
		}
		// last WSE mapper procedure.
		runWSENewMapper(mapperContext, mapper, input, output, split, umbilical);
	}

	private <INKEY, INVALUE, OUTKEY, OUTVALUE> void runWSENewMapper(
			org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE>.Context mapperContext,
			org.apache.hadoop.mapreduce.Mapper<INKEY, INVALUE, OUTKEY, OUTVALUE> mapper,
			org.apache.hadoop.mapreduce.RecordReader<INKEY, INVALUE> input,
			org.apache.hadoop.mapreduce.RecordWriter output, org.apache.hadoop.mapreduce.InputSplit split,
			TaskUmbilicalProtocol umbilical) throws IOException, InterruptedException {
		input.initialize(split, mapperContext);
		mapper.run(mapperContext);
		mapPhase.complete();
		setPhase(TaskStatus.Phase.SORT);
		statusUpdate(umbilical);
		input.close();
		output.close(mapperContext);
	}

	// For PSE.
	class PSEStatusWatcher implements Watcher {
		private ZooKeeperItf zk;
		private String checkpointNode;

		PSEStatusWatcher(ZooKeeperItf zk, String ckptZnode) {
			this.zk = zk;
			this.checkpointNode = ckptZnode;
		}

		@Override
		public void process(WatchedEvent we) {
			String path = we.getPath();
			byte[] data = null;
			if (we.getType() == Event.EventType.NodeDataChanged) {
				try {
					data = this.zk.getData(path, false, null);
					if (data != null) {
						String statusStr = new String(data);
						LOG.debug("WYG: PSEStatusWatcher get data [" + statusStr + "] from " + path);
						if (statusStr.equalsIgnoreCase("checkpointing")) {
							zk.exists(path, this);
						} else if (statusStr.equalsIgnoreCase("done")) {
							checkpointData = zk.getData(checkpointNode, false, null);
							synchronized (pseMapWaitObj) {
								isWaitTimeout = false;
								pseMapWaitObj.notifyAll();
							}
						}
					}
				} catch (KeeperException e) {
					LOG.warn("WYG: PSEStatusWatcher failed to getData from zk,", e);
				} catch (InterruptedException e) {
					LOG.warn("WYG: PSEStatusWatcher failed to getData from zk,", e);
				}
			}
		}
	}

	private URL getMapCheckpointURL(String httpAddr, int httpPort, JobID jobId, String taskIdStr, int startIndex,
			int numSpills) throws MalformedURLException {
		StringBuffer url = new StringBuffer("http://" + httpAddr + ":" + httpPort + "/mapCheckpoint?");

		url.append("job=" + jobId);
		url.append("&task=" + taskIdStr);
		url.append("&startIndex=" + startIndex);
		url.append("&numSpills=" + numSpills);

		LOG.debug("WYG: MapCheckpoint URL = " + url.toString());
		return new URL(url.toString());
	}

	private void connect(URLConnection connection, int connectionTimeout) throws IOException {
		int unit = 0;
		if (connectionTimeout < 0) {
			throw new IOException("Invalid timeout " + "[timeout = " + connectionTimeout + " ms]");
		} else if (connectionTimeout > 0) {
			unit = Math.min(60000, connectionTimeout);
		}
		// set the connect timeout to the unit-connect-timeout
		connection.setConnectTimeout(unit);
		while (true) {
			try {
				connection.connect();
				break;
			} catch (IOException ioe) {
				// update the total remaining connect-timeout
				connectionTimeout -= unit;

				// throw an exception if we have waited for timeout amount of
				// time
				// note that the updated value if timeout is used here
				if (connectionTimeout == 0) {
					throw ioe;
				}

				// reset the connect timeout for the last try
				if (connectionTimeout < unit) {
					unit = connectionTimeout;
					// reset the connect time out for the final connect
					connection.setConnectTimeout(unit);
				}
			}
		}
	}

	private long copyCheckpointData(ZipInputStream zin, FileSystem localFS) throws IOException {
		// v1: use BufferedInputStream to wrap zin
		/*
		 * BufferedInputStream bis = new BufferedInputStream(zin); ZipEntry ze;
		 * int len = -1, size = 0; byte[] buf = new byte[10*1024];
		 * BufferedOutputStream bos = null; while ((ze = zin.getNextEntry()) !=
		 * null) { LOG.debug("WYG: copy checkpointing file --> " + ze); Path p =
		 * mapOutputFile.getCheckpointFileForWrite(ze.toString()); bos = new
		 * BufferedOutputStream(localFS.create(p), 10*1024); size = 0; while
		 * ((len = bis.read(buf)) != -1) { size += len; bos.write(buf, 0, len);
		 * } bos.flush(); bos.close();
		 * 
		 * LOG.debug("WYG: file (" + p.toString() + ") size: " + size +
		 * " bytes from 'size', " + localFS.getFileStatus(p).getLen() +
		 * " bytes from File.getLen()."); }
		 */
		// v2: use zin directly
		long totalSize = 0L;
		int len = -1, size = 0;
		byte[] buf = new byte[10 * 1024];
		BufferedOutputStream bos = null;
		ZipEntry ze = zin.getNextEntry();
		while (ze != null) {
			LOG.debug("WYG: copy checkpointing file --> " + ze);
			//MTP-fix
			//Path p = mapOutputFile.getCheckpointFileForWrite(ze.toString());
			Path p = mapOutputFile.getCheckpointFileForWrite(ze);
			bos = new BufferedOutputStream(localFS.create(p), 10 * 1024);
			size = 0;
			while ((len = zin.read(buf)) != -1) {
				size += len;
				bos.write(buf, 0, len);
			}
			bos.flush();
			bos.close();

			zin.closeEntry();
			ze = zin.getNextEntry();

			LOG.debug("WYG: file (" + p.toString() + ") size: " + size + " bytes from 'size', "
					+ localFS.getFileStatus(p).getLen() + " bytes from File.getLen().");
			totalSize += localFS.getFileStatus(p).getLen();
		}
		zin.close();

		return totalSize;
	}

	class MapOutputCheckpoint implements Writable {
		private String httpAddress; // only id address
		private boolean needReduce = false;
		private String taskAttempt;
		private String file; // checkpoint zip file (no need) or hdfs file;
		private int startIndex; // spillIndexFileStartId;
		private int numSpills; //
		private long offset; // next spillNum or hdfs file offset

		public MapOutputCheckpoint() {
		}

		public MapOutputCheckpoint(String httpAddr, boolean needReduce, String taskAttempt, String file, int start,
				int num, long offset) {
			this.httpAddress = httpAddr;
			this.needReduce = needReduce;
			this.taskAttempt = taskAttempt;
			this.file = file;
			this.startIndex = start;
			this.numSpills = num;
			this.offset = offset;
		}

		/** use java IO */
		/*
		 * public byte[] toBytes() { ByteArrayOutputStream bos = new
		 * ByteArrayOutputStream(); ObjectOutputStream out = null; byte[] ret =
		 * null; try { out = new ObjectOutputStream(bos);
		 * out.writeInt(httpAddress.getBytes().length);
		 * out.writeBytes(httpAddress); out.writeBoolean(needReduce);
		 * out.writeInt(file.getBytes().length); out.writeBytes(file);
		 * out.writeInt(startIndex); out.writeInt(numSpills);
		 * out.writeLong(offset);
		 * 
		 * ret = bos.toByteArray(); } catch (IOException e) { LOG.
		 * warn("WYG: IOException in MapOutputCheckpoint.toBytes(), return null."
		 * , e); return null; } finally { try { if (out != null) { out.close();
		 * } } catch (IOException e) { // ignore } try { bos.close(); } catch
		 * (IOException e) { // ignore } } return ret; }
		 * 
		 * public void fromBytes(byte[] data) { ByteArrayInputStream bis = new
		 * ByteArrayInputStream(data); ObjectInputStream in = null; try { in =
		 * new ObjectInputStream(bis); // int len = in.readInt(); // byte[] b =
		 * new byte[len]; // in.readFully(b); // String httpAddress = new
		 * String(b); // boolean needReduce = in.readBoolean(); // len =
		 * in.readInt(); // b = new byte[len]; // in.readFully(b); // String
		 * file = new String(b); // int startIndex = in.readInt(); // int
		 * numSpills = in.readInt(); // long offset = in.readInt(); // return
		 * new MapOutputCheckpoint(httpAddress, needReduce, file, startIndex,
		 * numSpills, offset);
		 * 
		 * int len = in.readInt(); byte[] b = new byte[len]; in.readFully(b);
		 * this.httpAddress = new String(b); this.needReduce = in.readBoolean();
		 * len = in.readInt(); b = new byte[len]; in.readFully(b); this.file =
		 * new String(b); this.startIndex = in.readInt(); this.numSpills =
		 * in.readInt(); this.offset = in.readInt(); } catch (IOException e) {
		 * // ignore
		 * LOG.warn("WYG: IOException in MapOutputCheckpoint.fromBytes()", e); }
		 * finally { try { bis.close(); } catch (IOException e) { // ignore }
		 * try { if (in != null) { in.close(); } } catch (IOException e) { //
		 * ignore } } }
		 */
		/**
		 * use Hadoop IO
		 * 
		 * @throws IOEx\ception
		 */
		public byte[] toBytes() throws IOException {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);
			try {
				write(dos);
				return baos.toByteArray();
			} finally {
				if (dos != null) {
					dos.close();
				}
				baos.close();
			}
		}

		public void fromBytes(byte[] data) throws IOException {
			ByteArrayInputStream bais = new ByteArrayInputStream(data);
			DataInputStream dis = new DataInputStream(bais);
			try {
				this.readFields(dis);
			} finally {
				if (dis != null) {
					dis.close();
				}
				bais.close();
			}
		}

		@Override
		public String toString() {
			if (needReduce) {
				// map output is intermediate data
				return "MapOutputCheckpoint: HttpAddress=" + httpAddress + ", needReduce=" + needReduce
						+ ", taskAttempt=" + taskAttempt + ", IndexCheckpointFile=" + file + ", startIndex="
						+ startIndex + ", numSpills=" + numSpills;
			}
			// directly write to HDFS
			return "MapOutputCheckpoint: HttpAddress=" + httpAddress + ", needReduce=" + needReduce + ", taskAttempt="
					+ taskAttempt + ", OutputHdfsFile=" + file + ", offset=" + offset;
		}

		@Override
		public void readFields(DataInput in) throws IOException {
			this.httpAddress = in.readUTF();
			this.needReduce = in.readBoolean();
			this.taskAttempt = in.readUTF();
			this.file = in.readUTF();
			this.startIndex = in.readInt();
			this.numSpills = in.readInt();
			this.offset = in.readLong();
		}

		@Override
		public void write(DataOutput out) throws IOException {
			out.writeUTF(httpAddress);
			out.writeBoolean(needReduce);
			out.writeUTF(taskAttempt);
			out.writeUTF(file);
			out.writeInt(startIndex);
			out.writeInt(numSpills);
			out.writeLong(offset);
		}
	}

	interface MapOutputCollector<K, V> {

		public void collect(K key, V value, int partition) throws IOException, InterruptedException;

		public void close() throws IOException, InterruptedException;

		public void flush() throws IOException, InterruptedException, ClassNotFoundException;

		public MapOutputCheckpoint doCheckpoint() throws IOException;

		public void initFromCheckpoint(MapOutputCheckpoint mapOutputCheckpoint) throws IOException;
	}

	class DirectMapOutputCollector<K, V> implements MapOutputCollector<K, V> {

		private RecordWriter<K, V> out = null;

		private TaskReporter reporter = null;

		private final Counters.Counter mapOutputRecordCounter;

		@SuppressWarnings("unchecked")
		public DirectMapOutputCollector(TaskUmbilicalProtocol umbilical, JobConf job, TaskReporter reporter)
				throws IOException {
			this.reporter = reporter;
			String finalName = getOutputName(getPartition());

			FileSystem fs = FileSystem.get(job);

			out = job.getOutputFormat().getRecordWriter(fs, job, finalName, reporter);

			mapOutputRecordCounter = reporter.getCounter(TaskCounter.MAP_OUTPUT_RECORDS);
		}

		public void close() throws IOException {
			if (this.out != null) {
				out.close(this.reporter);
			}

		}

		public void flush() throws IOException, InterruptedException, ClassNotFoundException {
		}

		public void collect(K key, V value, int partition) throws IOException {
			reporter.progress();
			out.write(key, value);
			mapOutputRecordCounter.increment(1);
		}

		@Override
		public MapOutputCheckpoint doCheckpoint() {
			// TODO : to do, for map-only job
			boolean needReduce = false;
			return new MapOutputCheckpoint(getHttpAddr(), needReduce, "taskId[TODO]", "TODO", -1, -1, 0);
		}

		@Override
		public void initFromCheckpoint(MapOutputCheckpoint mapOutputCheckpoint) throws IOException {
			// TODO Auto-generated method stub

		}

	}

	private class MapOutputBuffer<K extends Object, V extends Object>
			implements MapOutputCollector<K, V>, IndexedSortable {
		final int partitions;
		final JobConf job;
		final TaskReporter reporter;
		final Class<K> keyClass;
		final Class<V> valClass;
		final RawComparator<K> comparator;
		final SerializationFactory serializationFactory;
		final Serializer<K> keySerializer;
		final Serializer<V> valSerializer;
		final CombinerRunner<K, V> combinerRunner;
		final CombineOutputCollector<K, V> combineCollector;

		// Compression for map-outputs
		final CompressionCodec codec;

		// k/v accounting
		final IntBuffer kvmeta; // metadata overlay on backing store
		int kvstart; // marks origin of spill metadata
		int kvend; // marks end of spill metadata
		int kvindex; // marks end of fully serialized records

		int equator; // marks origin of meta/serialization
		int bufstart; // marks beginning of spill
		int bufend; // marks beginning of collectable
		int bufmark; // marks end of record
		int bufindex; // marks end of collected
		int bufvoid; // marks the point where we should stop
						// reading at the end of the buffer

		byte[] kvbuffer; // main output buffer
		private final byte[] b0 = new byte[0];

		private static final int INDEX = 0; // index offset in acct
		private static final int VALSTART = 1; // val offset in acct
		private static final int KEYSTART = 2; // key offset in acct
		private static final int PARTITION = 3; // partition offset in acct
		private static final int NMETA = 4; // num meta ints
		private static final int METASIZE = NMETA * 4; // size in bytes

		// spill accounting
		final int maxRec;
		final int softLimit;
		boolean spillInProgress;
		int bufferRemaining;
		volatile Throwable sortSpillException = null;

		int numSpills = 0;
		final int minSpillsForCombine;
		final IndexedSorter sorter;
		final ReentrantLock spillLock = new ReentrantLock();
		final Condition spillDone = spillLock.newCondition();
		final Condition spillReady = spillLock.newCondition();
		final BlockingBuffer bb = new BlockingBuffer();
		volatile boolean spillThreadRunning = false;
		final SpillThread spillThread = new SpillThread();

		final FileSystem rfs;

		// Counters
		final Counters.Counter mapOutputByteCounter;
		final Counters.Counter mapOutputRecordCounter;

		final ArrayList<SpillRecord> indexCacheList = new ArrayList<SpillRecord>();
		private int totalIndexCacheMemory;
		private static final int INDEX_CACHE_MEMORY_LIMIT = 1024 * 1024;

		@SuppressWarnings("unchecked")
		public MapOutputBuffer(TaskUmbilicalProtocol umbilical, JobConf job, TaskReporter reporter)
				throws IOException, ClassNotFoundException {
			this.job = job;
			this.reporter = reporter;
			partitions = job.getNumReduceTasks();
			rfs = ((LocalFileSystem) FileSystem.getLocal(job)).getRaw();

			// sanity checks
			final float spillper = job.getFloat(JobContext.MAP_SORT_SPILL_PERCENT, (float) 0.8);
			final int sortmb = job.getInt(JobContext.IO_SORT_MB, 100);
			if (spillper > (float) 1.0 || spillper <= (float) 0.0) {
				throw new IOException("Invalid \"" + JobContext.MAP_SORT_SPILL_PERCENT + "\": " + spillper);
			}
			if ((sortmb & 0x7FF) != sortmb) {
				throw new IOException("Invalid \"" + JobContext.IO_SORT_MB + "\": " + sortmb);
			}
			sorter = ReflectionUtils.newInstance(job.getClass("map.sort.class", QuickSort.class, IndexedSorter.class),
					job);
			// buffers and accounting
			int maxMemUsage = sortmb << 20;
			maxMemUsage -= maxMemUsage % METASIZE;
			kvbuffer = new byte[maxMemUsage];
			bufvoid = kvbuffer.length;
			kvmeta = ByteBuffer.wrap(kvbuffer).asIntBuffer();
			setEquator(0);
			bufstart = bufend = bufindex = equator;
			kvstart = kvend = kvindex;

			maxRec = kvmeta.capacity() / NMETA;
			softLimit = (int) (kvbuffer.length * spillper);
			bufferRemaining = softLimit;
			if (LOG.isInfoEnabled()) {
				LOG.info(JobContext.IO_SORT_MB + ": " + sortmb);
				LOG.info("soft limit at " + softLimit);
				LOG.info("bufstart = " + bufstart + "; bufvoid = " + bufvoid);
				LOG.info("kvstart = " + kvstart + "; length = " + maxRec);
			}

			// k/v serialization
			comparator = job.getOutputKeyComparator();
			keyClass = (Class<K>) job.getMapOutputKeyClass();
			valClass = (Class<V>) job.getMapOutputValueClass();
			serializationFactory = new SerializationFactory(job);
			keySerializer = serializationFactory.getSerializer(keyClass);
			keySerializer.open(bb);
			valSerializer = serializationFactory.getSerializer(valClass);
			valSerializer.open(bb);

			// output counters
			mapOutputByteCounter = reporter.getCounter(TaskCounter.MAP_OUTPUT_BYTES);
			mapOutputRecordCounter = reporter.getCounter(TaskCounter.MAP_OUTPUT_RECORDS);

			// compression
			if (job.getCompressMapOutput()) {
				Class<? extends CompressionCodec> codecClass = job.getMapOutputCompressorClass(DefaultCodec.class);
				codec = ReflectionUtils.newInstance(codecClass, job);
			} else {
				codec = null;
			}

			// combiner
			final Counters.Counter combineInputCounter = reporter.getCounter(TaskCounter.COMBINE_INPUT_RECORDS);
			combinerRunner = CombinerRunner.create(job, getTaskID(), combineInputCounter, reporter, null);
			if (combinerRunner != null) {
				final Counters.Counter combineOutputCounter = reporter.getCounter(TaskCounter.COMBINE_OUTPUT_RECORDS);
				combineCollector = new CombineOutputCollector<K, V>(combineOutputCounter);
			} else {
				combineCollector = null;
			}
			spillInProgress = false;
			minSpillsForCombine = job.getInt(JobContext.MAP_COMBINE_MIN_SPILLS, 3);
			spillThread.setDaemon(true);
			spillThread.setName("SpillThread");
			spillLock.lock();
			try {
				spillThread.start();
				while (!spillThreadRunning) {
					spillDone.await();
				}
			} catch (InterruptedException e) {
				throw new IOException("Spill thread failed to initialize", e);
			} finally {
				spillLock.unlock();
			}
			if (sortSpillException != null) {
				throw new IOException("Spill thread failed to initialize", sortSpillException);
			}
		}

		/**
		 * Serialize the key, value to intermediate storage. When this method
		 * returns, kvindex must refer to sufficient unused storage to store one
		 * METADATA.
		 */
		public synchronized void collect(K key, V value, final int partition) throws IOException {
			reporter.progress();
			if (key.getClass() != keyClass) {
				throw new IOException("Type mismatch in key from map: expected " + keyClass.getName() + ", recieved "
						+ key.getClass().getName());
			}
			if (value.getClass() != valClass) {
				throw new IOException("Type mismatch in value from map: expected " + valClass.getName() + ", recieved "
						+ value.getClass().getName());
			}
			if (partition < 0 || partition >= partitions) {
				throw new IOException("Illegal partition for " + key + " (" + partition + ")");
			}
			checkSpillException();
			bufferRemaining -= METASIZE;
			if (bufferRemaining <= 0) {
				// start spill if the thread is not running and the soft limit
				// has been
				// reached
				spillLock.lock();
				try {
					do {
						if (!spillInProgress) {
							final int kvbidx = 4 * kvindex;
							final int kvbend = 4 * kvend;
							// serialized, unspilled bytes always lie between
							// kvindex and
							// bufindex, crossing the equator. Note that any
							// void space
							// created by a reset must be included in "used"
							// bytes
							final int bUsed = distanceTo(kvbidx, bufindex);
							final boolean bufsoftlimit = bUsed >= softLimit;
							if ((kvbend + METASIZE) % kvbuffer.length != equator - (equator % METASIZE)) {
								// spill finished, reclaim space
								resetSpill();
								bufferRemaining = Math.min(distanceTo(bufindex, kvbidx) - 2 * METASIZE,
										softLimit - bUsed) - METASIZE;
								continue;
							} else if (bufsoftlimit && kvindex != kvend) {
								// spill records, if any collected; check
								// latter, as it may
								// be possible for metadata alignment to hit
								// spill pcnt
								startSpill();
								final int avgRec = (int) (mapOutputByteCounter.getCounter()
										/ mapOutputRecordCounter.getCounter());
								// leave at least half the split buffer for
								// serialization data
								// ensure that kvindex >= bufindex
								final int distkvi = distanceTo(bufindex, kvbidx);
								final int newPos = (bufindex + Math.max(2 * METASIZE - 1,
										Math.min(distkvi / 2, distkvi / (METASIZE + avgRec) * METASIZE)))
										% kvbuffer.length;
								setEquator(newPos);
								bufmark = bufindex = newPos;
								final int serBound = 4 * kvend;
								// bytes remaining before the lock must be held
								// and limits
								// checked is the minimum of three arcs: the
								// metadata space, the
								// serialization space, and the soft limit
								bufferRemaining = Math.min(
										// metadata max
										distanceTo(bufend, newPos),
										Math.min(
												// serialization max
												distanceTo(newPos, serBound),
												// soft limit
												softLimit))
										- 2 * METASIZE;
							}
						}
					} while (false);
				} finally {
					spillLock.unlock();
				}
			}

			try {
				// serialize key bytes into buffer
				int keystart = bufindex;
				keySerializer.serialize(key);
				if (bufindex < keystart) {
					// wrapped the key; must make contiguous
					bb.shiftBufferedKey();
					keystart = 0;
				}
				// serialize value bytes into buffer
				final int valstart = bufindex;
				valSerializer.serialize(value);
				// It's possible for records to have zero length, i.e. the
				// serializer
				// will perform no writes. To ensure that the boundary
				// conditions are
				// checked and that the kvindex invariant is maintained, perform
				// a
				// zero-length write into the buffer. The logic monitoring this
				// could be
				// moved into collect, but this is cleaner and inexpensive. For
				// now, it
				// is acceptable.
				bb.write(b0, 0, 0);

				// the record must be marked after the preceding write, as the
				// metadata
				// for this record are not yet written
				int valend = bb.markRecord();

				mapOutputRecordCounter.increment(1);
				mapOutputByteCounter.increment(distanceTo(keystart, valend, bufvoid));

				// write accounting info
				kvmeta.put(kvindex + INDEX, kvindex);
				kvmeta.put(kvindex + PARTITION, partition);
				kvmeta.put(kvindex + KEYSTART, keystart);
				kvmeta.put(kvindex + VALSTART, valstart);
				// advance kvindex
				kvindex = (kvindex - NMETA + kvmeta.capacity()) % kvmeta.capacity();
			} catch (MapBufferTooSmallException e) {
				LOG.info("Record too large for in-memory buffer: " + e.getMessage());
				spillSingleRecord(key, value, partition);
				mapOutputRecordCounter.increment(1);
				return;
			}
		}

		/**
		 * Set the point from which meta and serialization data expand. The meta
		 * indices are aligned with the buffer, so metadata never spans the ends
		 * of the circular buffer.
		 */
		private void setEquator(int pos) {
			equator = pos;
			// set index prior to first entry, aligned at meta boundary
			final int aligned = pos - (pos % METASIZE);
			kvindex = ((aligned - METASIZE + kvbuffer.length) % kvbuffer.length) / 4;
			if (LOG.isInfoEnabled()) {
				LOG.info("(EQUATOR) " + pos + " kvi " + kvindex + "(" + (kvindex * 4) + ")");
			}
		}

		/**
		 * The spill is complete, so set the buffer and meta indices to be equal
		 * to the new equator to free space for continuing collection. Note that
		 * when kvindex == kvend == kvstart, the buffer is empty.
		 */
		private void resetSpill() {
			final int e = equator;
			bufstart = bufend = e;
			final int aligned = e - (e % METASIZE);
			// set start/end to point to first meta record
			kvstart = kvend = ((aligned - METASIZE + kvbuffer.length) % kvbuffer.length) / 4;
			if (LOG.isInfoEnabled()) {
				LOG.info("(RESET) equator " + e + " kv " + kvstart + "(" + (kvstart * 4) + ")" + " kvi " + kvindex + "("
						+ (kvindex * 4) + ")");
			}
		}

		/**
		 * Compute the distance in bytes between two indices in the
		 * serialization buffer.
		 * 
		 * @see #distanceTo(int,int,int)
		 */
		final int distanceTo(final int i, final int j) {
			return distanceTo(i, j, kvbuffer.length);
		}

		/**
		 * Compute the distance between two indices in the circular buffer given
		 * the max distance.
		 */
		int distanceTo(final int i, final int j, final int mod) {
			return i <= j ? j - i : mod - i + j;
		}

		/**
		 * For the given meta position, return the dereferenced position in the
		 * integer array. Each meta block contains several integers describing
		 * record data in its serialized form, but the INDEX is not necessarily
		 * related to the proximate metadata. The index value at the referenced
		 * int position is the start offset of the associated metadata block. So
		 * the metadata INDEX at metapos may point to the metadata described by
		 * the metadata block at metapos + k, which contains information about
		 * that serialized record.
		 */
		int offsetFor(int metapos) {
			return kvmeta.get(metapos * NMETA + INDEX);
		}

		/**
		 * Compare logical range, st i, j MOD offset capacity. Compare by
		 * partition, then by key.
		 * 
		 * @see IndexedSortable#compare
		 */
		public int compare(final int mi, final int mj) {
			final int kvi = offsetFor(mi % maxRec);
			final int kvj = offsetFor(mj % maxRec);
			final int kvip = kvmeta.get(kvi + PARTITION);
			final int kvjp = kvmeta.get(kvj + PARTITION);
			// sort by partition
			if (kvip != kvjp) {
				return kvip - kvjp;
			}
			// sort by key
			return comparator.compare(kvbuffer, kvmeta.get(kvi + KEYSTART),
					kvmeta.get(kvi + VALSTART) - kvmeta.get(kvi + KEYSTART), kvbuffer, kvmeta.get(kvj + KEYSTART),
					kvmeta.get(kvj + VALSTART) - kvmeta.get(kvj + KEYSTART));
		}

		/**
		 * Swap logical indices st i, j MOD offset capacity.
		 * 
		 * @see IndexedSortable#swap
		 */
		public void swap(final int mi, final int mj) {
			final int kvi = (mi % maxRec) * NMETA + INDEX;
			final int kvj = (mj % maxRec) * NMETA + INDEX;
			int tmp = kvmeta.get(kvi);
			kvmeta.put(kvi, kvmeta.get(kvj));
			kvmeta.put(kvj, tmp);
		}

		/**
		 * Inner class managing the spill of serialized records to disk.
		 */
		protected class BlockingBuffer extends DataOutputStream {

			public BlockingBuffer() {
				super(new Buffer());
			}

			/**
			 * Mark end of record. Note that this is required if the buffer is
			 * to cut the spill in the proper place.
			 */
			public int markRecord() {
				bufmark = bufindex;
				return bufindex;
			}

			/**
			 * Set position from last mark to end of writable buffer, then
			 * rewrite the data between last mark and kvindex. This handles a
			 * special case where the key wraps around the buffer. If the key is
			 * to be passed to a RawComparator, then it must be contiguous in
			 * the buffer. This recopies the data in the buffer back into
			 * itself, but starting at the beginning of the buffer. Note that
			 * this method should <b>only</b> be called immediately after
			 * detecting this condition. To call it at any other time is
			 * undefined and would likely result in data loss or corruption.
			 * 
			 * @see #markRecord()
			 */
			protected void shiftBufferedKey() throws IOException {
				// spillLock unnecessary; both kvend and kvindex are current
				int headbytelen = bufvoid - bufmark;
				bufvoid = bufmark;
				final int kvbidx = 4 * kvindex;
				final int kvbend = 4 * kvend;
				final int avail = Math.min(distanceTo(0, kvbidx), distanceTo(0, kvbend));
				if (bufindex + headbytelen < avail) {
					System.arraycopy(kvbuffer, 0, kvbuffer, headbytelen, bufindex);
					System.arraycopy(kvbuffer, bufvoid, kvbuffer, 0, headbytelen);
					bufindex += headbytelen;
					bufferRemaining -= kvbuffer.length - bufvoid;
				} else {
					byte[] keytmp = new byte[bufindex];
					System.arraycopy(kvbuffer, 0, keytmp, 0, bufindex);
					bufindex = 0;
					out.write(kvbuffer, bufmark, headbytelen);
					out.write(keytmp);
				}
			}
		}

		public class Buffer extends OutputStream {
			private final byte[] scratch = new byte[1];

			@Override
			public void write(int v) throws IOException {
				scratch[0] = (byte) v;
				write(scratch, 0, 1);
			}

			/**
			 * Attempt to write a sequence of bytes to the collection buffer.
			 * This method will block if the spill thread is running and it
			 * cannot write.
			 * 
			 * @throws MapBufferTooSmallException
			 *             if record is too large to deserialize into the
			 *             collection buffer.
			 */
			@Override
			public void write(byte b[], int off, int len) throws IOException {
				// must always verify the invariant that at least METASIZE bytes
				// are
				// available beyond kvindex, even when len == 0
				bufferRemaining -= len;
				if (bufferRemaining <= 0) {
					// writing these bytes could exhaust available buffer space
					// or fill
					// the buffer to soft limit. check if spill or blocking are
					// necessary
					boolean blockwrite = false;
					spillLock.lock();
					try {
						do {
							checkSpillException();

							final int kvbidx = 4 * kvindex;
							final int kvbend = 4 * kvend;
							// ser distance to key index
							final int distkvi = distanceTo(bufindex, kvbidx);
							// ser distance to spill end index
							final int distkve = distanceTo(bufindex, kvbend);

							// if kvindex is closer than kvend, then a spill is
							// neither in
							// progress nor complete and reset since the lock
							// was held. The
							// write should block only if there is insufficient
							// space to
							// complete the current write, write the metadata
							// for this record,
							// and write the metadata for the next record. If
							// kvend is closer,
							// then the write should block if there is too
							// little space for
							// either the metadata or the current write. Note
							// that collect
							// ensures its metadata requirement with a
							// zero-length write
							blockwrite = distkvi <= distkve ? distkvi <= len + 2 * METASIZE
									: distkve <= len || distanceTo(bufend, kvbidx) < 2 * METASIZE;

							if (!spillInProgress) {
								if (blockwrite) {
									if ((kvbend + METASIZE) % kvbuffer.length != equator - (equator % METASIZE)) {
										// spill finished, reclaim space
										// need to use meta exclusively;
										// zero-len rec & 100% spill
										// pcnt would fail
										resetSpill(); // resetSpill doesn't move
														// bufindex, kvindex
										bufferRemaining = Math.min(distkvi - 2 * METASIZE,
												softLimit - distanceTo(kvbidx, bufindex)) - len;
										continue;
									}
									// we have records we can spill; only spill
									// if blocked
									if (kvindex != kvend) {
										startSpill();
										// Blocked on this write, waiting for
										// the spill just
										// initiated to finish. Instead of
										// repositioning the marker
										// and copying the partial record, we
										// set the record start
										// to be the new equator
										setEquator(bufmark);
									} else {
										// We have no buffered records, and this
										// record is too large
										// to write into kvbuffer. We must spill
										// it directly from
										// collect
										final int size = distanceTo(bufstart, bufindex) + len;
										setEquator(0);
										bufstart = bufend = bufindex = equator;
										kvstart = kvend = kvindex;
										bufvoid = kvbuffer.length;
										throw new MapBufferTooSmallException(size + " bytes");
									}
								}
							}

							if (blockwrite) {
								// wait for spill
								try {
									while (spillInProgress) {
										reporter.progress();
										spillDone.await();
									}
								} catch (InterruptedException e) {
									throw new IOException("Buffer interrupted while waiting for the writer", e);
								}
							}
						} while (blockwrite);
					} finally {
						spillLock.unlock();
					}
				}
				// here, we know that we have sufficient space to write
				if (bufindex + len > bufvoid) {
					final int gaplen = bufvoid - bufindex;
					System.arraycopy(b, off, kvbuffer, bufindex, gaplen);
					len -= gaplen;
					off += gaplen;
					bufindex = 0;
				}
				System.arraycopy(b, off, kvbuffer, bufindex, len);
				bufindex += len;
			}
		}

		public void flush() throws IOException, ClassNotFoundException, InterruptedException {
			LOG.info("Starting flush of map output");
			spillLock.lock();
			try {
				while (spillInProgress) {
					reporter.progress();
					spillDone.await();
				}
				checkSpillException();

				final int kvbend = 4 * kvend;
				if ((kvbend + METASIZE) % kvbuffer.length != equator - (equator % METASIZE)) {
					// spill finished
					resetSpill();
				}
				if (kvindex != kvend) {
					kvend = (kvindex + NMETA) % kvmeta.capacity();
					bufend = bufmark;
					if (LOG.isInfoEnabled()) {
						LOG.info("Spilling map output");
						LOG.info("bufstart = " + bufstart + "; bufend = " + bufmark + "; bufvoid = " + bufvoid);
						LOG.info("kvstart = " + kvstart + "(" + (kvstart * 4) + "); kvend = " + kvend + "("
								+ (kvend * 4) + "); length = " + (distanceTo(kvend, kvstart, kvmeta.capacity()) + 1)
								+ "/" + maxRec);
					}
					sortAndSpill();
				}
			} catch (InterruptedException e) {
				throw new IOException("Interrupted while waiting for the writer", e);
			} finally {
				spillLock.unlock();
			}
			assert !spillLock.isHeldByCurrentThread();
			// shut down spill thread and wait for it to exit. Since the
			// preceding
			// ensures that it is finished with its work (and sortAndSpill did
			// not
			// throw), we elect to use an interrupt instead of setting a flag.
			// Spilling simultaneously from this thread while the spill thread
			// finishes its work might be both a useful way to extend this and
			// also
			// sufficient motivation for the latter approach.
			try {
				spillThread.interrupt();
				spillThread.join();
			} catch (InterruptedException e) {
				throw new IOException("Spill failed", e);
			}
			// release sort buffer before the merge
			kvbuffer = null;
			mergeParts();

			// for PSE
			tryToClearCheckpointFile(rfs);
		}

		public void close() {
		}

		protected class SpillThread extends Thread {

			@Override
			public void run() {
				spillLock.lock();
				spillThreadRunning = true;
				try {
					while (true) {
						spillDone.signal();
						while (!spillInProgress) {
							spillReady.await();
						}
						try {
							spillLock.unlock();
							sortAndSpill();
						} catch (Throwable t) {
							sortSpillException = t;
						} finally {
							spillLock.lock();
							if (bufend < bufstart) {
								bufvoid = kvbuffer.length;
							}
							kvstart = kvend;
							bufstart = bufend;
							spillInProgress = false;
						}
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					spillLock.unlock();
					spillThreadRunning = false;
				}
			}
		}

		private void checkSpillException() throws IOException {
			final Throwable lspillException = sortSpillException;
			if (lspillException != null) {
				if (lspillException instanceof Error) {
					final String logMsg = "Task " + getTaskID() + " failed : "
							+ StringUtils.stringifyException(lspillException);
					reportFatalError(getTaskID(), lspillException, logMsg);
				}
				throw new IOException("Spill failed", lspillException);
			}
		}

		private void startSpill() {
			assert !spillInProgress;
			kvend = (kvindex + NMETA) % kvmeta.capacity();
			bufend = bufmark;
			spillInProgress = true;
			if (LOG.isInfoEnabled()) {
				LOG.info("Spilling map output");
				LOG.info("bufstart = " + bufstart + "; bufend = " + bufmark + "; bufvoid = " + bufvoid);
				LOG.info("kvstart = " + kvstart + "(" + (kvstart * 4) + "); kvend = " + kvend + "(" + (kvend * 4)
						+ "); length = " + (distanceTo(kvend, kvstart, kvmeta.capacity()) + 1) + "/" + maxRec);
			}
			spillReady.signal();
		}

		private void sortAndSpill() throws IOException, ClassNotFoundException, InterruptedException {
			// approximate the length of the output file to be the length of the
			// buffer + header lengths for the partitions
			final long size = (bufend >= bufstart ? bufend - bufstart : (bufvoid - bufend) + bufstart)
					+ partitions * APPROX_HEADER_LENGTH;
			FSDataOutputStream out = null;
			try {
				// create spill file
				final SpillRecord spillRec = new SpillRecord(partitions);
				final Path filename = mapOutputFile.getSpillFileForWrite(numSpills, size);
				out = rfs.create(filename);

				final int mstart = kvend / NMETA;
				final int mend = 1 + // kvend is a valid record
						(kvstart >= kvend ? kvstart : kvmeta.capacity() + kvstart) / NMETA;
				sorter.sort(MapOutputBuffer.this, mstart, mend, reporter);
				int spindex = mstart;
				final IndexRecord rec = new IndexRecord();
				final InMemValBytes value = new InMemValBytes();
				for (int i = 0; i < partitions; ++i) {
					IFile.Writer<K, V> writer = null;
					try {
						long segmentStart = out.getPos();
						writer = new Writer<K, V>(job, out, keyClass, valClass, codec, spilledRecordsCounter);
						if (combinerRunner == null) {
							// spill directly
							DataInputBuffer key = new DataInputBuffer();
							while (spindex < mend && kvmeta.get(offsetFor(spindex % maxRec) + PARTITION) == i) {
								final int kvoff = offsetFor(spindex % maxRec);
								key.reset(kvbuffer, kvmeta.get(kvoff + KEYSTART),
										(kvmeta.get(kvoff + VALSTART) - kvmeta.get(kvoff + KEYSTART)));
								getVBytesForOffset(kvoff, value);
								writer.append(key, value);
								++spindex;
							}
						} else {
							int spstart = spindex;
							while (spindex < mend && kvmeta.get(offsetFor(spindex % maxRec) + PARTITION) == i) {
								++spindex;
							}
							// Note: we would like to avoid the combiner if
							// we've fewer
							// than some threshold of records for a partition
							if (spstart != spindex) {
								combineCollector.setWriter(writer);
								RawKeyValueIterator kvIter = new MRResultIterator(spstart, spindex);
								combinerRunner.combine(kvIter, combineCollector);
							}
						}

						// close the writer
						writer.close();

						// record offsets
						rec.startOffset = segmentStart;
						rec.rawLength = writer.getRawLength();
						rec.partLength = writer.getCompressedLength();
						spillRec.putIndex(rec, i);

						writer = null;
					} finally {
						if (null != writer)
							writer.close();
					}
				}

				if (totalIndexCacheMemory >= INDEX_CACHE_MEMORY_LIMIT) {
					// create spill index file
					Path indexFilename = mapOutputFile.getSpillIndexFileForWrite(numSpills,
							partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
					spillRec.writeToFile(indexFilename, job);
				} else {
					indexCacheList.add(spillRec);
					totalIndexCacheMemory += spillRec.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
				}
				LOG.info("Finished spill " + numSpills);
				++numSpills;
			} finally {
				if (out != null)
					out.close();
			}
		}

		/**
		 * Handles the degenerate case where serialization fails to fit in the
		 * in-memory buffer, so we must spill the record from collect directly
		 * to a spill file. Consider this "losing".
		 */
		private void spillSingleRecord(final K key, final V value, int partition) throws IOException {
			long size = kvbuffer.length + partitions * APPROX_HEADER_LENGTH;
			FSDataOutputStream out = null;
			try {
				// create spill file
				final SpillRecord spillRec = new SpillRecord(partitions);
				final Path filename = mapOutputFile.getSpillFileForWrite(numSpills, size);
				out = rfs.create(filename);

				// we don't run the combiner for a single record
				IndexRecord rec = new IndexRecord();
				for (int i = 0; i < partitions; ++i) {
					IFile.Writer<K, V> writer = null;
					try {
						long segmentStart = out.getPos();
						// Create a new codec, don't care!
						writer = new IFile.Writer<K, V>(job, out, keyClass, valClass, codec, spilledRecordsCounter);

						if (i == partition) {
							final long recordStart = out.getPos();
							writer.append(key, value);
							// Note that our map byte count will not be accurate
							// with
							// compression
							mapOutputByteCounter.increment(out.getPos() - recordStart);
						}
						writer.close();

						// record offsets
						rec.startOffset = segmentStart;
						rec.rawLength = writer.getRawLength();
						rec.partLength = writer.getCompressedLength();
						spillRec.putIndex(rec, i);

						writer = null;
					} catch (IOException e) {
						if (null != writer)
							writer.close();
						throw e;
					}
				}
				if (totalIndexCacheMemory >= INDEX_CACHE_MEMORY_LIMIT) {
					// create spill index file
					Path indexFilename = mapOutputFile.getSpillIndexFileForWrite(numSpills,
							partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH);
					spillRec.writeToFile(indexFilename, job);
				} else {
					indexCacheList.add(spillRec);
					totalIndexCacheMemory += spillRec.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
				}
				++numSpills;
			} finally {
				if (out != null)
					out.close();
			}
		}

		/**
		 * Given an offset, populate vbytes with the associated set of
		 * deserialized value bytes. Should only be called during a spill.
		 */
		private void getVBytesForOffset(int kvoff, InMemValBytes vbytes) {
			// get the keystart for the next serialized value to be the end
			// of this value. If this is the last value in the buffer, use
			// bufend
			final int nextindex = kvoff == kvend ? bufend
					: kvmeta.get((kvoff - NMETA + kvmeta.capacity() + KEYSTART) % kvmeta.capacity());
			// calculate the length of the value
			int vallen = (nextindex >= kvmeta.get(kvoff + VALSTART)) ? nextindex - kvmeta.get(kvoff + VALSTART)
					: (bufvoid - kvmeta.get(kvoff + VALSTART)) + nextindex;
			vbytes.reset(kvbuffer, kvmeta.get(kvoff + VALSTART), vallen);
		}

		/**
		 * Inner class wrapping valuebytes, used for appendRaw.
		 */
		protected class InMemValBytes extends DataInputBuffer {
			private byte[] buffer;
			private int start;
			private int length;

			public void reset(byte[] buffer, int start, int length) {
				this.buffer = buffer;
				this.start = start;
				this.length = length;

				if (start + length > bufvoid) {
					this.buffer = new byte[this.length];
					final int taillen = bufvoid - start;
					System.arraycopy(buffer, start, this.buffer, 0, taillen);
					System.arraycopy(buffer, 0, this.buffer, taillen, length - taillen);
					this.start = 0;
				}

				super.reset(this.buffer, this.start, this.length);
			}
		}

		protected class MRResultIterator implements RawKeyValueIterator {
			private final DataInputBuffer keybuf = new DataInputBuffer();
			private final InMemValBytes vbytes = new InMemValBytes();
			private final int end;
			private int current;

			public MRResultIterator(int start, int end) {
				this.end = end;
				current = start - 1;
			}

			public boolean next() throws IOException {
				return ++current < end;
			}

			public DataInputBuffer getKey() throws IOException {
				final int kvoff = offsetFor(current % maxRec);
				keybuf.reset(kvbuffer, kvmeta.get(kvoff + KEYSTART),
						kvmeta.get(kvoff + VALSTART) - kvmeta.get(kvoff + KEYSTART));
				return keybuf;
			}

			public DataInputBuffer getValue() throws IOException {
				getVBytesForOffset(offsetFor(current % maxRec), vbytes);
				return vbytes;
			}

			public Progress getProgress() {
				return null;
			}

			public void close() {
			}
		}

		private void mergeParts() throws IOException, InterruptedException, ClassNotFoundException {
			// get the approximate size of the final output/index files
			long finalOutFileSize = 0;
			long finalIndexFileSize = 0;
			final Path[] filename = new Path[numSpills];
			final TaskAttemptID mapId = getTaskID();

			for (int i = 0; i < numSpills; i++) {
				filename[i] = mapOutputFile.getSpillFile(i);
				finalOutFileSize += rfs.getFileStatus(filename[i]).getLen();
			}
			if (numSpills == 1) { // the spill is the final output
				rfs.rename(filename[0], new Path(filename[0].getParent(), "file.out"));
				if (indexCacheList.size() == 0) {
					rfs.rename(mapOutputFile.getSpillIndexFile(0), new Path(filename[0].getParent(), "file.out.index"));
				} else {
					indexCacheList.get(0).writeToFile(new Path(filename[0].getParent(), "file.out.index"), job);
				}
				return;
			}

			// read in paged indices
			for (int i = indexCacheList.size(); i < numSpills; ++i) {
				Path indexFileName = mapOutputFile.getSpillIndexFile(i);
				indexCacheList.add(new SpillRecord(indexFileName, job));
			}

			// make correction in the length to include the sequence file header
			// lengths for each partition
			finalOutFileSize += partitions * APPROX_HEADER_LENGTH;
			finalIndexFileSize = partitions * MAP_OUTPUT_INDEX_RECORD_LENGTH;
			Path finalOutputFile = mapOutputFile.getOutputFileForWrite(finalOutFileSize);
			Path finalIndexFile = mapOutputFile.getOutputIndexFileForWrite(finalIndexFileSize);

			// The output stream for the final single output file
			FSDataOutputStream finalOut = rfs.create(finalOutputFile, true, 4096);

			if (numSpills == 0) {
				// create dummy files
				IndexRecord rec = new IndexRecord();
				SpillRecord sr = new SpillRecord(partitions);
				try {
					for (int i = 0; i < partitions; i++) {
						long segmentStart = finalOut.getPos();
						Writer<K, V> writer = new Writer<K, V>(job, finalOut, keyClass, valClass, codec, null);
						writer.close();
						rec.startOffset = segmentStart;
						rec.rawLength = writer.getRawLength();
						rec.partLength = writer.getCompressedLength();
						sr.putIndex(rec, i);
					}
					sr.writeToFile(finalIndexFile, job);
				} finally {
					finalOut.close();
				}
				// For check pse correctness
				LOG.info("WYG-PSE Check: finalOutputFile:" + rfs.getFileStatus(finalOutputFile).getLen());
				LOG.info("WYG-PSE Check: finalIndexFile:" + rfs.getFileStatus(finalIndexFile).getLen());

				return;
			}
			{
				sortPhase.addPhases(partitions); // Divide sort phase into
													// sub-phases
				Merger.considerFinalMergeForProgress();

				IndexRecord rec = new IndexRecord();
				final SpillRecord spillRec = new SpillRecord(partitions);
				for (int parts = 0; parts < partitions; parts++) {
					// create the segments to be merged
					List<Segment<K, V>> segmentList = new ArrayList<Segment<K, V>>(numSpills);
					for (int i = 0; i < numSpills; i++) {
						IndexRecord indexRecord = indexCacheList.get(i).getIndex(parts);

						Segment<K, V> s = new Segment<K, V>(job, rfs, filename[i], indexRecord.startOffset,
								indexRecord.partLength, codec, true);
						segmentList.add(i, s);

						if (LOG.isDebugEnabled()) {
							LOG.debug("MapId=" + mapId + " Reducer=" + parts + "Spill =" + i + "("
									+ indexRecord.startOffset + "," + indexRecord.rawLength + ", "
									+ indexRecord.partLength + ")");
						}
					}

					int mergeFactor = job.getInt(JobContext.IO_SORT_FACTOR, 100);
					// sort the segments only if there are intermediate merges
					boolean sortSegments = segmentList.size() > mergeFactor;
					// merge
					@SuppressWarnings("unchecked")
					RawKeyValueIterator kvIter = Merger.merge(job, rfs, keyClass, valClass, codec, segmentList,
							mergeFactor, new Path(mapId.toString()), job.getOutputKeyComparator(), reporter,
							sortSegments, null, spilledRecordsCounter, sortPhase.phase());

					// write merged output to disk
					long segmentStart = finalOut.getPos();
					Writer<K, V> writer = new Writer<K, V>(job, finalOut, keyClass, valClass, codec,
							spilledRecordsCounter);
					if (combinerRunner == null || numSpills < minSpillsForCombine) {
						Merger.writeFile(kvIter, writer, reporter, job);
					} else {
						combineCollector.setWriter(writer);
						combinerRunner.combine(kvIter, combineCollector);
					}

					// close
					writer.close();

					sortPhase.startNextPhase();

					// record offsets
					rec.startOffset = segmentStart;
					rec.rawLength = writer.getRawLength();
					rec.partLength = writer.getCompressedLength();
					spillRec.putIndex(rec, parts);
				}
				spillRec.writeToFile(finalIndexFile, job);
				finalOut.close();
				for (int i = 0; i < numSpills; i++) {
					rfs.delete(filename[i], true);
				}

				// For check pse correctness
				LOG.info("WYG-PSE Check: finalOutputFile:" + rfs.getFileStatus(finalOutputFile).getLen());
				LOG.info("WYG-PSE Check: finalIndexFile:" + rfs.getFileStatus(finalIndexFile).getLen());

			}
		}

		// FIXME for PSE
		@Override
		public MapOutputCheckpoint doCheckpoint() throws IOException {
			boolean needReduce = true;
			long t0 = System.currentTimeMillis();
			// (I): Spill actively
			LOG.debug("WYG: (I) Starting Spill actively.");
			spillLock.lock();
			try {
				while (spillInProgress) {
					reporter.progress();
					spillDone.await();
				}
				checkSpillException();

				final int kvbend = 4 * kvend;
				if ((kvbend + METASIZE) % kvbuffer.length != equator - (equator % METASIZE)) {
					// spill finished
					resetSpill();
				}
				if (kvindex != kvend) {
					kvend = (kvindex + NMETA) % kvmeta.capacity();
					bufend = bufmark;
					/*
					 * if (LOG.isInfoEnabled()) {
					 * LOG.debug("Spilling map output"); LOG.debug("bufstart = "
					 * + bufstart + "; bufend = " + bufmark + "; bufvoid = " +
					 * bufvoid); LOG.debug("kvstart = " + kvstart + "(" +
					 * (kvstart * 4) + "); kvend = " + kvend + "(" + (kvend * 4)
					 * + "); length = " + (distanceTo(kvend, kvstart,
					 * kvmeta.capacity()) + 1) + "/" + maxRec); }
					 */
					sortAndSpill();
				}
			} catch (InterruptedException e) {
				throw new IOException("Interrupted while waiting for the writer", e);
			} catch (ClassNotFoundException e) {
				throw new IOException("ClassNotFoundException while spilling actively", e);
			} finally {
				spillLock.unlock();
			}
			LOG.debug("WYG: (I) Finished to Spill actively, takes " + (System.currentTimeMillis() - t0) + " ms.");

			// (II): make indexCacheList data into a checkpoint file
			Path indexCheckpointFilePath = mapOutputFile.getOutputIndexCheckpointFile();
			LOG.debug("WYG: (II) Begin to make indexCacheList data into a checkpoint file: "
					+ indexCheckpointFilePath.getName());
			t0 = System.currentTimeMillis();
			saveSpillRecordListToFile(indexCacheList, indexCheckpointFilePath);
			LOG.debug("WYG: (II) Finished to saveSpillRecordListToFile(), takes " + (System.currentTimeMillis() - t0)
					+ " ms. IndexCheckpointFile has " + rfs.getFileStatus(indexCheckpointFilePath).getLen()
					+ " bytes.");

			/*
			 * Since zip procedure takes time, (eg: Zipping 7 spill file and a
			 * index ckpt file, about 273MB, takes 17 seconds), let SE task
			 * fetch several files instread. Note that it needs to tell the
			 * spillIndexFileStartId
			 */
			int spillIndexFileStartId = indexCacheList.size();
			/*
			 * // (III): zip indexCheckpointFile/index files/spilling files to
			 * one file--> ckpt.zip Path ckptZipFilePath =
			 * mapOutputFile.getOutputCheckpointZipFile();
			 * LOG.debug("WYG: (III) Begin to zip all files into one file: " +
			 * ckptZipFilePath.getName()); t0 = System.currentTimeMillis();
			 * List<Path> files = new ArrayList<Path>(); for (int i = 0; i <
			 * numSpills; i++) { files.add(mapOutputFile.getSpillFile(i)); }
			 * LOG.
			 * info("WYG: before adding index file, indexCacheList.size() = " +
			 * indexCacheList.size() + ", numSpills = " + numSpills); if
			 * (indexCacheList.size() > 0 && numSpills > 0) { for (int i =
			 * indexCacheList.size(); i < numSpills; i++) {
			 * files.add(mapOutputFile.getSpillIndexFile(i)); } }
			 * files.add(indexCheckpointFilePath); zipCheckpointFile(files,
			 * ckptZipFilePath); LOG.debug("WYG: (III) Finished to zip ( " +
			 * files.size() + " ) files into one file, takes " +
			 * (System.currentTimeMillis() - t0) + " ms.");
			 */
			return new MapOutputCheckpoint(getHttpAddr(), needReduce, getTaskID().toString(),
					indexCheckpointFilePath.getName(), spillIndexFileStartId, numSpills, 0);
		}

		@Override
		public void initFromCheckpoint(MapOutputCheckpoint mapOutputCheckpoint) throws IOException {
			if (mapOutputCheckpoint == null) {
				LOG.warn("WYG: initFromCheckpoint() method should not receive a null InParameter!!!");
				return;
			}
			// TODO:
			LOG.info("WYG: Start to init Writer from check point.");
			numSpills = mapOutputCheckpoint.numSpills;

			LOG.info("WYG: mapOutputCheckpoint.file = " + mapOutputCheckpoint.file);
			Path ckptPath = mapOutputFile.getCheckpointFileForRead(mapOutputCheckpoint.file);
			totalIndexCacheMemory = loadSpillRecordListFromFile(indexCacheList, ckptPath);

			LOG.info("WYG: Finished to initFromCheckpoint.");
			// TODO....how about the buffer?

		}

		// for PSE
		public void saveSpillRecordListToFile(final ArrayList<SpillRecord> cacheList, Path path) throws IOException {
			LOG.info("WYG: Before saveSpillRecordListToFile(), indexCacheList.size=" + indexCacheList.size()
					+ ", totalIndexCacheMemory=" + totalIndexCacheMemory);
			DataOutputStream out = new DataOutputStream(rfs.create(path));
			try {
				out.writeInt(cacheList.size()); // # of spill records
				for (SpillRecord sr : cacheList) {
					sr.writeToStream(out);
				}
				// LOG.debug("WYG: in saveSpillRecordListToFile(), out.size = "
				// +
				// out.size());
				out.flush();
			} finally {
				if (out != null) {
					out.close();
				}
			}
		}

		// for PSE
		/**
		 * 
		 * @param cacheList
		 * @param path
		 * @return totalIndexCacheMemory
		 * @throws IOException
		 */
		public int loadSpillRecordListFromFile(ArrayList<SpillRecord> cacheList, Path path) throws IOException {
			int total = 0;
			DataInputStream in = new DataInputStream(rfs.open(path));
			// FIXME, should know partitions first
			try {
				int n = in.readInt();
				for (int i = 0; i < n; ++i) {
					SpillRecord sr = new SpillRecord(partitions);
					sr.readFromStream(in);
					cacheList.add(sr);
					total += sr.size() * MAP_OUTPUT_INDEX_RECORD_LENGTH;
				}
			} finally {
				if (in != null) {
					in.close();
				}
			}
			LOG.info("WYG: After loadSpillRecordListFromFile(), indexCacheList.size=" + cacheList.size()
					+ ", totalIndexCacheMemory=" + total);
			return total;
		}

		private void zipCheckpointFile(List<Path> files, Path ckptPath) throws IOException {
			ZipOutputStream zos = new ZipOutputStream(rfs.create(ckptPath));
			BufferedOutputStream out = new BufferedOutputStream(zos);
			int totalSize = 0;
			for (Path path : files) {
				BufferedInputStream in = new BufferedInputStream(rfs.open(path));
				// FIXME: path.getName is enough???
				zos.putNextEntry(new ZipEntry(path.getName()));
				byte[] b = new byte[1024];
				int len = 0;
				while ((len = in.read(b)) > 0) {
					out.write(b, 0, len);
					totalSize += len;
				}
				in.close();
				out.flush();
			}
			out.close();
			LOG.info("WYG: Zip file's total size = " + totalSize);
		}

		// FIXME for PSE, to clear ckpt file if necessary
		public void tryToClearCheckpointFile(FileSystem fs) throws IOException {
			Path indexCheckpointFilePath = mapOutputFile.getOutputIndexCheckpointFile();
			if (rfs.exists(indexCheckpointFilePath)) {
				rfs.deleteOnExit(indexCheckpointFilePath);
			}
		}

	} // MapOutputBuffer

	/**
	 * Exception indicating that the allocated sort buffer is insufficient to
	 * hold the current record.
	 */
	@SuppressWarnings("serial")
	private static class MapBufferTooSmallException extends IOException {
		public MapBufferTooSmallException(String s) {
			super(s);
		}
	}

}
