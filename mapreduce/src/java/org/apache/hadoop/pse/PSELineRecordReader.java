package org.apache.hadoop.pse;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.Seekable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.compress.SplitCompressionInputStream;
import org.apache.hadoop.io.compress.SplittableCompressionCodec;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.MapContext;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.input.LineRecordReader;
import org.apache.hadoop.util.LineReader;

public class PSELineRecordReader extends PSERecordReader<LongWritable, Text> {
  private static final Log LOG = LogFactory.getLog(PSELineRecordReader.class);
  public static final String MAX_LINE_LENGTH = 
    "mapreduce.input.linerecordreader.line.maxlength";

  private CompressionCodecFactory compressionCodecs = null;
  private long start;
  private long pos;
  private long end;
  private LineReader in;
  private FSDataInputStream fileIn;
  private Seekable filePosition;
  private int maxLineLength;
  private LongWritable key = null;
  private Text value = null;
  private Counter inputByteCounter;
  private CompressionCodec codec;
  private Decompressor decompressor;

  public void initialize(InputSplit genericSplit,
                         TaskAttemptContext context) throws IOException {
    LOG.debug("PSE: This is not a PSE task.");
    
    FileSplit split = (FileSplit) genericSplit;
    inputByteCounter = ((MapContext)context).getCounter(
      FileInputFormat.COUNTER_GROUP, FileInputFormat.BYTES_READ);
    Configuration job = context.getConfiguration();
    this.maxLineLength = job.getInt(MAX_LINE_LENGTH, Integer.MAX_VALUE);
    start = split.getStart();
    end = start + split.getLength();
    final Path file = split.getPath();
    compressionCodecs = new CompressionCodecFactory(job);
    codec = compressionCodecs.getCodec(file);

    // open the file and seek to the start of the split
    final FileSystem fs = file.getFileSystem(job);
    fileIn = fs.open(file);
    if (isCompressedInput()) {
      //Un-support now!!!
      throw new IOException("PSE: Unsupport compressed InputStream, because it does not know how to seek to a certain position now.");
    } else {
      fileIn.seek(start);
      in = new LineReader(fileIn, job);
      filePosition = fileIn;
    }
    // If this is not the first split, we always throw away first record
    // because we always (except the last split) read one extra line in
    // next() method.
    if (start != 0) {
      start += in.readLine(new Text(), 0, maxBytesToConsume(start));
    }  
    this.pos = start;
  }
  
  @Override
  /**
   * For PSE.
   */
  public void initialize(InputSplit genericSplit, long position,
      TaskAttemptContext context) throws IOException {
    FileSplit split = (FileSplit) genericSplit;
    inputByteCounter = ((MapContext)context).getCounter(
      FileInputFormat.COUNTER_GROUP, FileInputFormat.BYTES_READ);
    Configuration job = context.getConfiguration();
    this.maxLineLength = job.getInt(MAX_LINE_LENGTH, Integer.MAX_VALUE);
    start = split.getStart();
    end = start + split.getLength();
    final Path file = split.getPath();
    compressionCodecs = new CompressionCodecFactory(job);
    codec = compressionCodecs.getCodec(file);

    // open the file and seek to the start of the split
    final FileSystem fs = file.getFileSystem(job);
    fileIn = fs.open(file);
    if (isCompressedInput()) {
      //Un-support now!!!
      throw new IOException("Unsupport compressed InputStream, because it does not know how to seek to a certain position in PSE.");
    }
    
    /******* PSE task seeks to a MapInput checkpoint. Always entering the if-clause when calling this initialize method ***/
    if(position > start && position <end) {
      // for PSE, special case:
      LOG.debug("PSE: PSELineRecordReader seeks to " + position);
      fileIn.seek(position);
      in = new LineReader(fileIn, job);
      filePosition = fileIn;
      
      this.pos = position;
      
      return;
    }

    /**
     * default, need not to seek
     */
    fileIn.seek(start);
    in = new LineReader(fileIn, job);
    filePosition = fileIn;

    // If this is not the first split, we always throw away first record
    // because we always (except the last split) read one extra line in
    // next() method.
    if (start != 0) {
      start += in.readLine(new Text(), 0, maxBytesToConsume(start));
    }

    this.pos = start;
  }
  
  private boolean isCompressedInput() {
    return (codec != null);
  }

  private int maxBytesToConsume(long pos) {
    return isCompressedInput()
      ? Integer.MAX_VALUE
      : (int) Math.min(Integer.MAX_VALUE, end - pos);
  }

  private long getFilePosition() throws IOException {
    long retVal;
    if (isCompressedInput() && null != filePosition) {
      retVal = filePosition.getPos();
    } else {
      retVal = pos;
    }
    return retVal;
  }

  public boolean nextKeyValue() throws IOException {
    if (key == null) {
      key = new LongWritable();
    }
    key.set(pos);
    if (value == null) {
      value = new Text();
    }
    int newSize = 0;
    // We always read one extra line, which lies outside the upper
    // split limit i.e. (end - 1)
    while (getFilePosition() <= end) {
      newSize = in.readLine(value, maxLineLength,
          Math.max(maxBytesToConsume(pos), maxLineLength));
      if (newSize == 0) {
        break;
      }
      pos += newSize;
      inputByteCounter.increment(newSize);
      if (newSize < maxLineLength) {
        break;
      }

      // line too long. try again
      LOG.info("Skipped line of size " + newSize + " at pos " + 
               (pos - newSize));
    }
    if (newSize == 0) {
      key = null;
      value = null;
      return false;
    } else {
      return true;
    }
  }

  @Override
  public LongWritable getCurrentKey() {
    return key;
  }

  @Override
  public Text getCurrentValue() {
    return value;
  }
  
  // FIXME: for PSE
  public  synchronized long getPos() throws IOException {
    return pos;
  }

  /**
   * Get the progress within the split
   */
  public float getProgress() throws IOException {
    if (start == end) {
      return 0.0f;
    } else {
      return Math.min(1.0f, (getFilePosition() - start) / (float)(end - start));
    }
  }
  
  public synchronized void close() throws IOException {
    try {
      if (in != null) {
        in.close();
      }
    } finally {
      if (decompressor != null) {
        CodecPool.returnDecompressor(decompressor);
      }
    }
  }
}
