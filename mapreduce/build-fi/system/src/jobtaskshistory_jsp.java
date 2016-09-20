package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import org.apache.jasper.runtime.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import org.apache.hadoop.http.HtmlQuoting;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.*;
import java.text.SimpleDateFormat;
import org.apache.hadoop.mapreduce.jobhistory.*;

public class jobtaskshistory_jsp extends HttpJspBase {

	
  private static SimpleDateFormat dateFormat =
                                    new SimpleDateFormat("d/MM HH:mm:ss") ; 

	private static final long serialVersionUID = 1L;


  private void printTask(String logFile,
    JobHistoryParser.TaskAttemptInfo attempt, JspWriter out) throws IOException{
    out.print("<tr>"); 
    out.print("<td>" + "<a href=\"taskdetailshistory.jsp?logFile="+ logFile 
        +"&tipid="+attempt.getAttemptId().getTaskID().toString() +"\">" +
          attempt.getAttemptId().getTaskID() + "</a></td>");
    out.print("<td>" + StringUtils.getFormattedTimeWithDiff(dateFormat, 
          attempt.getStartTime(), 0 ) + "</td>");
    out.print("<td>" + StringUtils.getFormattedTimeWithDiff(dateFormat, 
          attempt.getFinishTime(),
          attempt.getStartTime() ) + "</td>");
    out.print("<td>"+ HtmlQuoting.quoteHtmlChars(attempt.getError()) +"</td>");
    out.print("</tr>"); 
  }


  private static java.util.Vector _jspx_includes;

  public java.util.List getIncludes() {
    return _jspx_includes;
  }

  public void _jspService(HttpServletRequest request, HttpServletResponse response)
        throws java.io.IOException, ServletException {

    JspFactory _jspxFactory = null;
    javax.servlet.jsp.PageContext pageContext = null;
    HttpSession session = null;
    ServletContext application = null;
    ServletConfig config = null;
    JspWriter out = null;
    Object page = this;
    JspWriter _jspx_out = null;


    try {
      _jspxFactory = JspFactory.getDefaultFactory();
      response.setContentType("text/html; charset=UTF-8");
      pageContext = _jspxFactory.getPageContext(this, request, response,
      			null, true, 8192, true);
      application = pageContext.getServletContext();
      config = pageContext.getServletConfig();
      session = pageContext.getSession();
      out = pageContext.getOut();
      _jspx_out = out;


/*
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

      out.write("\n\n\n");
            out.write("\n");
      out.write("\n\n");
	
  String logFile = request.getParameter("logFile");
  String taskStatus = request.getParameter("status"); 
  String taskType = request.getParameter("taskType"); 
  
  FileSystem fs = (FileSystem) application.getAttribute("fileSys");
  JobTracker jobTracker = (JobTracker) application.getAttribute("job.tracker");
  JobHistoryParser.JobInfo job = JSPUtil.checkAccessAndGetJobInfo(request,
      response, jobTracker, fs, new Path(logFile));
  if (job == null) {
    return;
  }
  Map<TaskID, JobHistoryParser.TaskInfo> tasks = job.getAllTasks(); 

      out.write("\n<html>\n<body>\n<h2>");
                        out.print(taskStatus);
      out.write(" ");
      out.print(taskType );
      out.write(" task list for <a href=\"jobdetailshistory.jsp?logFile=");
            out.print(logFile);
      out.write("\">");
      out.print(job.getJobId() );
      out.write(" </a></h2>\n<center>\n<table border=\"2\" cellpadding=\"5\" cellspacing=\"2\">\n<tr><td>Task Id</td><td>Start Time</td><td>Finish Time<br/></td><td>Error</td></tr>\n");
                                                                                          
  for (JobHistoryParser.TaskInfo task : tasks.values()) {
    if (taskType.equalsIgnoreCase(task.getTaskType().toString())) {
      Map <TaskAttemptID, JobHistoryParser.TaskAttemptInfo> taskAttempts = task.getAllTaskAttempts();
      for (JobHistoryParser.TaskAttemptInfo taskAttempt : taskAttempts.values()) {
        if (taskStatus.equals(taskAttempt.getTaskStatus()) || 
          taskStatus.equalsIgnoreCase("all")){
          printTask(logFile, taskAttempt, out); 
        }
      }
    }
  }

      out.write("\n</table>\n");
            out.write("\n</center>\n</body>\n</html>\n");
                      } catch (Throwable t) {
      out = _jspx_out;
      if (out != null && out.getBufferSize() != 0)
        out.clearBuffer();
      if (pageContext != null) pageContext.handlePageException(t);
    } finally {
      if (_jspxFactory != null) _jspxFactory.releasePageContext(pageContext);
    }
  }
}
