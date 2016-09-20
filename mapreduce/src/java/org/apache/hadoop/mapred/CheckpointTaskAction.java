package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class CheckpointTaskAction extends TaskTrackerAction {  
  private TaskAttemptID taskId;

  protected CheckpointTaskAction() {
    super(ActionType.CHECKPOINT_TASK);
    taskId = new TaskAttemptID();
  }
  
  public CheckpointTaskAction(TaskAttemptID taskid) {
    super(ActionType.CHECKPOINT_TASK);
    taskId = taskid;
  }
  
  public TaskAttemptID getTaskID() {
    return taskId;
  }
  
  public void write(DataOutput out) throws IOException {
    taskId.write(out);
  }

  public void readFields(DataInput in) throws IOException {
    taskId.readFields(in);
  }

}
