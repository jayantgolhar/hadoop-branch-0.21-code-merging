package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import org.apache.jasper.runtime.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.util.*;
import org.apache.hadoop.mapreduce.jobhistory.*;
import org.apache.hadoop.mapreduce.JobACL;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.security.AccessControlException;

public class jobconf_history_jsp extends HttpJspBase {

	private static final long serialVersionUID = 1L;


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
            out.write("\n\n");

  JobTracker tracker = (JobTracker) application.getAttribute("job.tracker");

  String logFileString = request.getParameter("logFile");
  if (logFileString == null) {
    out.println("<h2>Missing 'logFile' for fetching job configuration!</h2>");
    return;
  }

  Path logFile = new Path(logFileString);
  String jobId = JobHistory.getJobIDFromHistoryFilePath(logFile).toString();


      out.write("\n  \n<html>\n\n<title>Job Configuration: JobId - ");
                  out.print( jobId );
      out.write("</title>\n\n<body>\n<h2>Job Configuration: JobId - ");
                  out.print( jobId );
      out.write("</h2><br>\n\n");
      
  Path jobFilePath = JSPUtil.getJobConfFilePath(logFile);
  FileSystem fs = (FileSystem) application.getAttribute("fileSys");
  FSDataInputStream jobFile = null; 
  try {
    jobFile = fs.open(jobFilePath);
    JobConf jobConf = new JobConf(jobFilePath);
    JobTracker jobTracker = (JobTracker) application.getAttribute("job.tracker");

    JobHistoryParser.JobInfo job = JSPUtil.checkAccessAndGetJobInfo(request,
        response, jobTracker, fs, logFile);
    if (job == null) {
      return;
    }

    XMLUtils.transform(
        jobConf.getConfResourceAsInputStream("webapps/static/jobconf.xsl"),
        jobFile, out);
  } catch (Exception e) {
    out.println("Failed to retreive job configuration for job '" + jobId + "!");
    out.println(e);
  } finally {
    if (jobFile != null) {
      try { 
        jobFile.close(); 
      } catch (IOException e) {}
    }
  } 

      out.write("\n\n<br>\n");
      
out.println(ServletUtil.htmlFooter());

      out.write("\n");
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
