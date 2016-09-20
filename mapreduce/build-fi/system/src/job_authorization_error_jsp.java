package org.apache.hadoop.mapred;

import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.jsp.*;
import org.apache.jasper.runtime.*;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.net.URL;
import org.apache.hadoop.util.*;

public class job_authorization_error_jsp extends HttpJspBase {

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

      out.write("\n\n");
            out.write("\n\n<html>\n<head>\n<title>Error: User cannot access this Job</title>\n</head>\n<body>\n<h2>Error: User cannot do this operation on this Job</h2><br>\n\n");
                                                      
  String errorMsg = (String) request.getAttribute("error.msg");

      out.write("\n\n<font size=\"5\"> \n");
      
  out.println(errorMsg);

      out.write("\n</font>\n\n<hr>\n\n");
            
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
