package org.apache.hadoop.mapred;

/*
 * In order to trace the speculative task state for a TIP
 * Add by ygwang
 */
public enum SpeculativeTaskState {
  Unassigned,  //TIP has not speculative task, or the previous SE task is over but has been scheduled another one
//  Chosing,   //TaskScheduler assigned a speculative task for a TIP, but has not generated TaskAttemptID
  Chosen,    //TIP has a speculative task, and obtained the unique TaskAttemptID
//  Running,   //TIP has a running SE task
  End      //FIXME the SE task is over, maybe it's succeed or killed.
}
