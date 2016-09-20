package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import org.apache.jasper.runtime.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.util.*;
import org.apache.hadoop.http.HtmlQuoting;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.mapred.JSPUtil.JobWithViewAccessCheck;
import org.apache.hadoop.util.*;

public class jobblacklistedtrackers_jsp extends HttpJspBase {

	private static final long serialVersionUID = 1L;

       
  private void printBlackListedTrackers(JspWriter out, 
                             JobInProgress job) throws IOException {
    Map<String, Integer> trackerErrors = job.getTaskTrackerErrors();
    out.print("<table border=2 cellpadding=\"5\" cellspacing=\"2\">");
    out.print("<tr><th>TaskTracker</th><th>No. of Failures</th></tr>\n");
    int maxErrorsPerTracker = job.getJobConf().getMaxTaskFailuresPerTracker();
    for (Map.Entry<String,Integer> e : trackerErrors.entrySet()) {
      if (e.getValue().intValue() >= maxErrorsPerTracker) {
        out.print("<tr><td>" + HtmlQuoting.quoteHtmlChars(e.getKey()) +
            "</td><td>" + e.getValue() + "</td></tr>\n");
      }
    }
    out.print("</table>\n");
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

      out.write("\n\n");
            out.write("\n\n");

  JobTracker tracker = (JobTracker) application.getAttribute(
      "job.tracker");
  String trackerName = 
           StringUtils.simpleHostname(tracker.getJobTrackerMachine());

      out.write("\n");
      out.write("\n\n");

    String jobId = request.getParameter("jobid");
    if (jobId == null) {
  	  out.println("<h2>Missing 'jobid' for fetching black-listed tasktrackers!</h2>");
  	  return;
    }
    
    JobWithViewAccessCheck myJob = JSPUtil.checkAccessAndGetJob(tracker,
        JobID.forName(jobId), request, response);
    if (!myJob.isViewJobAllowed()) {
      return; // user is not authorized to view this job
    }

    JobInProgress job = myJob.getJob();
    if (job == null) {
      out.print("<b>Job " + jobId + " not found.</b><br>\n");
      return;
    }

      out.write("\n\n<html>\n<title>Hadoop ");
                  out.print(jobId);
      out.write("'s black-listed tasktrackers</title>\n<body>\n<h1>Hadoop <a href=\"jobdetails.jsp?jobid=");
                              out.print(jobId);
      out.write("\">");
      out.print(jobId);
      out.write("</a> - \nBlack-listed task-trackers</h1>\n\n");
       
    printBlackListedTrackers(out, job); 

      out.write("\n\n<hr>\n<a href=\"jobdetails.jsp?jobid=");
                  out.print(jobId);
      out.write("\">Go back to ");
      out.print(jobId);
      out.write("</a><br>\n");
      
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
