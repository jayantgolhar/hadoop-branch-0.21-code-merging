package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import org.apache.jasper.runtime.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.util.Vector;
import java.util.Collection;
import org.apache.hadoop.http.HtmlQuoting;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.util.ServletUtil;

public class jobqueue_details_jsp extends HttpJspBase {

private static final long serialVersionUID = 526456771152222127L;

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
            out.write("\n");

  JobTracker tracker = (JobTracker) application.getAttribute("job.tracker");
  String trackerName = StringUtils.simpleHostname(tracker
      .getJobTrackerMachine());
  String queueName = request.getParameter("queueName");
  TaskScheduler scheduler = tracker.getTaskScheduler();
  JobQueueInfo schedInfo = tracker.getQueueInfo(queueName);

      out.write("\n<html>\n<head>\n<title>Queue details for ");
                        out.print(queueName != null ? queueName : "");
      out.write(" </title>\n<link rel=\"stylesheet\" type=\"text/css\" href=\"/static/hadoop.css\">\n<script type=\"text/javascript\" src=\"/static/jobtracker.js\"></script>\n</head>\n<body>\n");
                                    
  if (!JSPUtil.processButtons(request, response, tracker)) {
    return;// user is not authorized
  }

      out.write("\n");

  String schedulingInfoString = schedInfo.getSchedulingInfo();

      out.write("\n<h1>Hadoop Job Queue Scheduling Information on \n  <a href=\"jobtracker.jsp\">");
                  out.print(trackerName);
      out.write("</a>\n</h1>\n<div>\nScheduling Information :\n");
                  out.print(HtmlQuoting.quoteHtmlChars(schedulingInfoString).replaceAll("\n", "<br/>"));
      out.write("\n</div>\n<hr/>\n");
            
  if (schedInfo.getChildren() != null && schedInfo.getChildren().size() > 0) {

      out.write("\nChild Queues : \n");

    for (JobQueueInfo childQueue : schedInfo.getChildren()) {
      String[] childNameSplits = childQueue.getQueueName().split(":");
      String childName = childNameSplits[childNameSplits.length -1];

      out.write("\n      <a href=\"jobqueue_details.jsp?queueName=");
            out.print(childQueue.getQueueName());
      out.write("\">\n      ");
      out.print(childName);
      out.write("</a>&nbsp &nbsp\n");

    }

      out.write("\n<br/>\n");
      
  } else {
    Collection<JobInProgress> jobs = scheduler.getJobs(queueName);
    if (jobs == null || jobs.isEmpty()) {

      out.write("\n<center>\n<h2> No Jobs found for the Queue :: ");
                  out.print(queueName != null ? queueName : "");
      out.write(" </h2>\n<hr/>\n</center>\n");
                  
  } else {

      out.write("\n<center>\n<h2> Job Summary for the Queue :: ");
                  out.print(queueName != null ? queueName : "");
      out.write(" </h2>\n</center>\n<div style=\"text-align: center;text-indent: center;font-style: italic;\">\n(In the order maintained by the scheduler)\n</div>\n<br/>\n<hr/>\n");
                                          out.print(JSPUtil.generateJobTable("Job List", jobs, 30, 0, tracker.conf));
      out.write("\n<hr>\n");
      
  }
  }

      out.write("\n\n");

  out.println(ServletUtil.htmlFooter());

      out.write("\n\n");
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
