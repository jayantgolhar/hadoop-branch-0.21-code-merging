package org.apache.hadoop.pse;

import java.io.IOException;

import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;

public abstract class PSERecordReader<K, V> extends RecordReader<K, V> {

  public abstract void initialize(InputSplit split, long position,
      TaskAttemptContext context
      ) throws IOException;
  
}
