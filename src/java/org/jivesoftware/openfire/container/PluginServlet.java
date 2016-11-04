/**
 * $Revision: 3067 $
 * $Date: 2005-11-12 22:29:01 -0300 (Sat, 12 Nov 2005) $
 *
 * Copyright (C) 2004-2008 Jive Software. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jivesoftware.openfire.container;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.*;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.jasper.JasperException;
import org.apache.jasper.JspC;
import org.dom4j.*;
import org.dom4j.io.SAXReader;
import org.jivesoftware.admin.PluginFilter;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.StringUtils;
import org.jivesoftware.util.WebXmlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

/**
 * The plugin servlet acts as a proxy for web requests (in the admin console)
 * to plugins. Since plugins can be dynamically loaded and live in a different place
 * than normal Openfire admin console files, it's not possible to have them
 * added to the normal Openfire admin console web app directory.
 * <p>
 * The servlet listens for requests in the form <tt>/plugins/[pluginName]/[JSP File]</tt>
 * (e.g. <tt>/plugins/foo/example.jsp</tt>). It also listens for non JSP requests in the
 * form like <tt>/plugins/[pluginName]/images/*.png|gif</tt>,
 * <tt>/plugins/[pluginName]/scripts/*.js|css</tt> or
 * <tt>/plugins/[pluginName]/styles/*.css</tt> (e.g.
 * <tt>/plugins/foo/images/example.gif</tt>).
 * </p>
 * JSP files must be compiled and available via the plugin's class loader. The mapping
 * between JSP name and servlet class files is defined in [pluginName]/web/web.xml.
 * Typically, this file is auto-generated by the JSP compiler when packaging the plugin.
 * Alternatively, if development mode is enabled for the plugin then the the JSP file
 * will be dynamically compiled using JSPC.
 *
 * @author Matt Tucker
 */
public class PluginServlet extends HttpServlet {

	private static final Logger Log = LoggerFactory.getLogger(PluginServlet.class);

    private static Map<String, GenericServlet> servlets;  // mapped using lowercase path (OF-1105)
    private static PluginManager pluginManager;
    private static ServletConfig servletConfig;

    static {
        servlets = new ConcurrentHashMap<>();
    }
	
	public static final String PLUGINS_WEBROOT = "/plugins/";

    @Override
	public void init(ServletConfig config) throws ServletException {
        super.init(config);
        servletConfig = config;
    }

    @Override
	public void service(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
        else {
            try {
                // Handle JSP requests.
                if (pathInfo.endsWith(".jsp")) {
                    if (handleDevJSP(pathInfo, request, response)) {
                        return;
                    }
                    handleJSP(pathInfo, request, response);
                }
                // Handle servlet requests.
                else if (getServlet(pathInfo) != null) {
                    handleServlet(pathInfo, request, response);
                }
                // Handle image/other requests.
                else {
                    handleOtherRequest(pathInfo, response);
                }
                // Do not allow framing; OF-997
                response.addHeader("X-Frame-Options", JiveGlobals.getProperty("adminConsole.frame-options", "deny"));
            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            }
        }
    }

    /**
     * Registers all JSP page servlets for a plugin.
     *
     * @param manager the plugin manager.
     * @param plugin the plugin.
     * @param webXML the web.xml file containing JSP page names to servlet class file
     *      mappings.
     */
    public static void registerServlets( PluginManager manager, final Plugin plugin, File webXML)
    {
        pluginManager = manager;

        if ( !webXML.exists() )
        {
            Log.error("Could not register plugin servlets, file " + webXML.getAbsolutePath() + " does not exist.");
            return;
        }

        // Find the name of the plugin directory given that the webXML file lives in plugins/[pluginName]/web/web.xml
        final String pluginName = webXML.getParentFile().getParentFile().getParentFile().getName();
        try
        {
            final Document webXmlDoc = WebXmlUtils.asDocument( webXML );

            final List<String> servletNames = WebXmlUtils.getServletNames( webXmlDoc );
            for ( final String servletName : servletNames )
            {
                Log.debug( "Loading servlet '{}' of plugin '{}'...", servletName, pluginName );

                final String className = WebXmlUtils.getServletClassName( webXmlDoc, servletName );
                if ( className == null || className.isEmpty() )
                {
                    Log.warn( "Could not load servlet '{}' of plugin '{}'. web-xml does not define a class name for this servlet.", servletName, pluginName );
                    continue;
                }

                final Class theClass = manager.loadClass( plugin, className );

                final Object instance = theClass.newInstance();
                if ( !(instance instanceof GenericServlet) )
                {
                    Log.warn( "Could not load servlet '{}' of plugin '{}'. Its class ({}) is not an instance of javax.servlet.GenericServlet.", servletName, pluginName, className );
                    continue;
                }

                Log.debug( "Initializing servlet '{}' of plugin '{}'...", servletName, pluginName );
                ( (GenericServlet) instance ).init( servletConfig );

                Log.debug( "Registering servlet '{}' of plugin '{}' URL patterns.", servletName, pluginName );
                final Set<String> urlPatterns = WebXmlUtils.getServletUrlPatterns( webXmlDoc, servletName );
                for ( final String urlPattern : urlPatterns )
                {
                    servlets.put( ( pluginName + urlPattern ).toLowerCase(), (GenericServlet) instance );
                }
                Log.debug( "Servlet '{}' of plugin '{}' loaded successfully.", servletName, pluginName );
            }


            final List<String> filterNames = WebXmlUtils.getFilterNames( webXmlDoc );
            for ( final String filterName : filterNames )
            {
                Log.debug( "Loading filter '{}' of plugin '{}'...", filterName, pluginName );
                final String className = WebXmlUtils.getFilterClassName( webXmlDoc, filterName );
                if ( className == null || className.isEmpty() )
                {
                    Log.warn( "Could not load filter '{}' of plugin '{}'. web-xml does not define a class name for this filter.", filterName, pluginName );
                    continue;
                }
                final Class theClass = manager.loadClass( plugin, className );

                final Object instance = theClass.newInstance();
                if ( !(instance instanceof Filter) )
                {
                    Log.warn( "Could not load filter '{}' of plugin '{}'. Its class ({}) is not an instance of javax.servlet.Filter.", filterName, pluginName, className );
                    continue;
                }

                Log.debug( "Initializing filter '{}' of plugin '{}'...", filterName, pluginName );
                ( (Filter) instance ).init( new FilterConfig()
                {
                    @Override
                    public String getFilterName()
                    {
                        return filterName;
                    }

                    @Override
                    public ServletContext getServletContext()
                    {
                        return new PluginServletContext( servletConfig.getServletContext(), pluginManager, plugin );
                    }

                    @Override
                    public String getInitParameter( String s )
                    {
                        final Map<String, String> params = WebXmlUtils.getFilterInitParams( webXmlDoc, filterName );
                        if ( params == null || params.isEmpty() )
                        {
                            return null;
                        }
                        return params.get( s );
                    }

                    @Override
                    public Enumeration<String> getInitParameterNames()
                    {
                        final Map<String, String> params = WebXmlUtils.getFilterInitParams( webXmlDoc, filterName );;
                        if ( params == null || params.isEmpty() )
                        {
                            return Collections.emptyEnumeration();
                        }
                        return Collections.enumeration( params.keySet() );
                    }
                } );

                Log.debug( "Registering filter '{}' of plugin '{}' URL patterns.", filterName, pluginName );
                final Set<String> urlPatterns = WebXmlUtils.getFilterUrlPatterns( webXmlDoc, filterName );
                for ( final String urlPattern : urlPatterns )
                {
                    PluginFilter.addPluginFilter( urlPattern, ( (Filter) instance ) );
                }
                Log.debug( "Filter '{}' of plugin '{}' loaded successfully.", filterName, pluginName );
            }
        }
        catch (Throwable e)
        {
            Log.error( "An unexpected problem occurred while attempting to register servlets for plugin '{}'.", plugin, e);
        }
    }

    /**
     * Unregisters all JSP page servlets for a plugin.
     *
     * @param webXML the web.xml file containing JSP page names to servlet class file
     *               mappings.
     */
    public static void unregisterServlets(File webXML)
    {
        if ( !webXML.exists() )
        {
            Log.error("Could not unregister plugin servlets, file " + webXML.getAbsolutePath() + " does not exist.");
            return;
        }

        // Find the name of the plugin directory given that the webXML file lives in plugins/[pluginName]/web/web.xml
        final String pluginName = webXML.getParentFile().getParentFile().getParentFile().getName();
        try
        {
            final Document webXmlDoc = WebXmlUtils.asDocument( webXML );

            // Un-register and destroy all servlets.
            final List<String> servletNames = WebXmlUtils.getServletNames( webXmlDoc );
            for ( final String servletName : servletNames )
            {
                Log.debug( "Unregistering servlet '{}' of plugin '{}'", servletName, pluginName );
                final Set<Servlet> toDestroy = new HashSet<>();
                final Set<String> urlPatterns = WebXmlUtils.getServletUrlPatterns( webXmlDoc, servletName );
                for ( final String urlPattern : urlPatterns )
                {
                    final GenericServlet servlet = servlets.remove( ( pluginName + urlPattern ).toLowerCase() );
                    if (servlet != null)
                    {
                        toDestroy.add( servlet );
                    }
                }

                for ( final Servlet servlet : toDestroy )
                {
                    servlet.destroy();
                }

                Log.debug( "Servlet '{}' of plugin '{}' unregistered and destroyed successfully.", servletName, pluginName );

            }

            // Un-register and destroy all servlet filters.
            final List<String> filterNames = WebXmlUtils.getFilterNames( webXmlDoc );
            for ( final String filterName : filterNames )
            {
                Log.debug( "Unregistering filter '{}' of plugin '{}'", filterName, pluginName );
                final Set<Filter> toDestroy = new HashSet<>();
                final String className = WebXmlUtils.getFilterClassName( webXmlDoc, filterName );
                final Set<String> urlPatterns = WebXmlUtils.getFilterUrlPatterns( webXmlDoc, filterName );
                for ( final String urlPattern : urlPatterns )
                {
                    final Filter filter = PluginFilter.removePluginFilter( urlPattern, className );
                    if (filter != null)
                    {
                        toDestroy.add( filter );
                    }
                }

                for ( final Filter filter : toDestroy )
                {
                    filter.destroy();
                }

                Log.debug( "Filter '{}' of plugin '{}' unregistered and destroyesd successfully.", filterName, pluginName );
            }
        }
        catch (Throwable e) {
            Log.error( "An unexpected problem occurred while attempting to unregister servlets.", e);
        }
    }

	/**
	 * Registers a live servlet for a plugin programmatically, does not
	 * initialize the servlet.
	 * 
	 * @param pluginManager the plugin manager
	 * @param plugin the owner of the servlet
	 * @param servlet the servlet.
	 * @param relativeUrl the relative url where the servlet should be bound
	 * @return the effective url that can be used to initialize the servlet
	 */
	public static String registerServlet(PluginManager pluginManager,
			Plugin plugin, GenericServlet servlet, String relativeUrl)
			throws ServletException {

		String pluginName = pluginManager.getPluginDirectory(plugin).getName();
		PluginServlet.pluginManager = pluginManager;
		if (servlet == null) {
			throw new ServletException("Servlet is missing");
		}
		String pluginServletUrl = pluginName + relativeUrl;
		servlets.put((pluginName + relativeUrl).toLowerCase(), servlet);
		return PLUGINS_WEBROOT + pluginServletUrl;
		
	}

	/**
	 * Unregister a live servlet for a plugin programmatically. Does not call
	 * the servlet destroy method.
	 * 
	 * @param plugin the owner of the servlet
	 * @param url the relative url where servlet has been bound
	 * @return the unregistered servlet, so that it can be destroyed
	 */
	public static GenericServlet unregisterServlet(Plugin plugin, String url)
			throws ServletException {
		String pluginName = pluginManager.getPluginDirectory(plugin).getName();
		if (url == null) {
			throw new ServletException("Servlet URL is missing");
		}
		String fullUrl = pluginName + url;
		GenericServlet servlet = servlets.remove(fullUrl.toLowerCase());
		return servlet;
	}
    
    /**
     * Handles a request for a JSP page. It checks to see if a servlet is mapped
     * for the JSP URL. If one is found, request handling is passed to it. If no
     * servlet is found, a 404 error is returned.
     *
     * @param pathInfo the extra path info.
     * @param request  the request object.
     * @param response the response object.
     * @throws ServletException if a servlet exception occurs while handling the request.
     * @throws IOException      if an IOException occurs while handling the request.
     */
    private void handleJSP(String pathInfo, HttpServletRequest request,
                           HttpServletResponse response) throws ServletException, IOException {
        // Strip the starting "/" from the path to find the JSP URL.
        String jspURL = pathInfo.substring(1);

        GenericServlet servlet = servlets.get(jspURL.toLowerCase());
        if (servlet != null) {
            servlet.service(request, response);
        }
        else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * Handles a request for a Servlet. If one is found, request handling is passed to it.
     * If no servlet is found, a 404 error is returned.
     *
     * @param pathInfo the extra path info.
     * @param request  the request object.
     * @param response the response object.
     * @throws ServletException if a servlet exception occurs while handling the request.
     * @throws IOException      if an IOException occurs while handling the request.
     */
    private void handleServlet(String pathInfo, HttpServletRequest request,
                               HttpServletResponse response) throws ServletException, IOException {
        // Strip the starting "/" from the path to find the JSP URL.
        GenericServlet servlet = getServlet(pathInfo);
        if (servlet != null) {
            servlet.service(request, response);
        }
        else {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }

    /**
     * Returns the correct servlet with mapping checks.
     *
     * @param pathInfo the pathinfo to map to the servlet.
     * @return the mapped servlet, or null if no servlet was found.
     */
    private GenericServlet getServlet(String pathInfo) {
        pathInfo = pathInfo.substring(1).toLowerCase();

        GenericServlet servlet = servlets.get(pathInfo);
        if (servlet == null) {
            for (String key : servlets.keySet()) {
                int index = key.indexOf("/*");
                String searchkey = key;
                if (index != -1) {
                    searchkey = key.substring(0, index);
                }
                if (searchkey.startsWith(pathInfo) || pathInfo.startsWith(searchkey)) {
                    servlet = servlets.get(key);
                    break;
                }
            }
        }
        return servlet;
    }


    /**
     * Handles a request for other web items (images, flash, applets, etc.)
     *
     * @param pathInfo the extra path info.
     * @param response the response object.
     * @throws IOException if an IOException occurs while handling the request.
     */
    private void handleOtherRequest(String pathInfo, HttpServletResponse response) throws IOException {
        String[] parts = pathInfo.split("/");
        // Image request must be in correct format.
        if (parts.length < 3) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        String contextPath = "";
        int index = pathInfo.indexOf(parts[1]);
        if (index != -1) {
            contextPath = pathInfo.substring(index + parts[1].length());
        }

        File pluginDirectory = new File(JiveGlobals.getHomeDirectory(), "plugins");
        File file = new File(pluginDirectory, parts[1] + File.separator + "web" + contextPath);

        // When using dev environment, the images dir may be under something other that web.
        Plugin plugin = pluginManager.getPlugin(parts[1]);
        PluginDevEnvironment environment = pluginManager.getDevEnvironment(plugin);

        if (environment != null) {
            file = new File(environment.getWebRoot(), contextPath);
        }
        if (!file.exists()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        else {
            // Content type will be GIF or PNG.
            String contentType = "image/gif";
            if (pathInfo.endsWith(".png")) {
                contentType = "image/png";
            }
            else if (pathInfo.endsWith(".swf")) {
                contentType = "application/x-shockwave-flash";
            }
            else if (pathInfo.endsWith(".css")) {
                contentType = "text/css";
            }
            else if (pathInfo.endsWith(".js")) {
                contentType = "text/javascript";
            }
            else if (pathInfo.endsWith(".html") || pathInfo.endsWith(".htm")) {
                contentType = "text/html";
            }

            // setting the content-disposition header breaks IE when downloading CSS
            // response.setHeader("Content-disposition", "filename=\"" + file + "\";");
            response.setContentType(contentType);
            // Write out the resource to the user.
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                try (ServletOutputStream out = response.getOutputStream()) {

                    // Set the size of the file.
                    response.setContentLength((int) file.length());

                    // Use a 1K buffer.
                    byte[] buf = new byte[1024];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }
            }
        }
    }


    /**
     * Handles a request for a JSP page in development mode. If development mode is
     * not enabled, this method returns false so that normal JSP handling can be performed.
     * If development mode is enabled, this method tries to locate the JSP, compile
     * it using JSPC, and then return the output.
     *
     * @param pathInfo the extra path info.
     * @param request  the request object.
     * @param response the response object.
     * @return true if this page request was handled; false if the request was not handled.
     */
    private boolean handleDevJSP(String pathInfo, HttpServletRequest request,
                                 HttpServletResponse response) {
        String jspURL = pathInfo.substring(1);

        // Handle pre-existing pages and fail over to pre-compiled pages.
        int fileSeperator = jspURL.indexOf("/");
        if (fileSeperator != -1) {
            String pluginName = jspURL.substring(0, fileSeperator);
            Plugin plugin = pluginManager.getPlugin(pluginName);

            PluginDevEnvironment environment = pluginManager.getDevEnvironment(plugin);
            // If development mode not turned on for plugin, return false.
            if (environment == null) {
                return false;
            }
            File webDir = environment.getWebRoot();
            if (webDir == null || !webDir.exists()) {
                return false;
            }

            File pluginDirectory = pluginManager.getPluginDirectory(plugin);

            File compilationDir = new File(pluginDirectory, "classes");
            compilationDir.mkdirs();

            String jsp = jspURL.substring(fileSeperator + 1);

            int indexOfLastSlash = jsp.lastIndexOf("/");
            String relativeDir = "";
            if (indexOfLastSlash != -1) {
                relativeDir = jsp.substring(0, indexOfLastSlash);
                relativeDir = relativeDir.replaceAll("//", ".") + '.';
            }

            File jspFile = new File(webDir, jsp);
            String filename = jspFile.getName();
            int indexOfPeriod = filename.indexOf(".");
            if (indexOfPeriod != -1) {
                filename = "dev" + StringUtils.randomString(4);
            }

            JspC jspc = new JspC();
            if (!jspFile.exists()) {
                return false;
            }
            try {
                jspc.setJspFiles(jspFile.getCanonicalPath());
            }
            catch (IOException e) {
                Log.error(e.getMessage(), e);
            }
            jspc.setOutputDir(compilationDir.getAbsolutePath());
            jspc.setClassName(filename);
            jspc.setCompile(true);

            jspc.setClassPath(getClasspathForPlugin(plugin));
            jspc.execute();

            try {
                Object servletInstance = pluginManager.loadClass(plugin, "org.apache.jsp." +
                    relativeDir + filename).newInstance();
                HttpServlet servlet = (HttpServlet)servletInstance;
                servlet.init(servletConfig);
                servlet.service(request, response);
                return true;
            }
            catch (Exception e) {
                Log.error(e.getMessage(), e);
            }
        }
        return false;
    }

    /**
     * Returns the classpath to use for the JSPC Compiler.
     *
     * @param plugin the plugin the jspc will handle.
     * @return the classpath needed to compile a single jsp in a plugin.
     */
    private static String getClasspathForPlugin(Plugin plugin) {
        final StringBuilder classpath = new StringBuilder();

        File pluginDirectory = pluginManager.getPluginDirectory(plugin);

        PluginDevEnvironment pluginEnv = pluginManager.getDevEnvironment(plugin);

        PluginClassLoader pluginClassloader = pluginManager.getPluginClassloader(plugin);

        for (URL url : pluginClassloader.getURLs()) {
            File file = new File(url.getFile());

            classpath.append(file.getAbsolutePath()).append(';');
        }

        // Load all jars from lib
        File libDirectory = new File(pluginDirectory, "lib");
        File[] libs = libDirectory.listFiles();
        final int no = libs != null ? libs.length : 0;
        for (int i = 0; i < no; i++) {
            File libFile = libs[i];
            classpath.append(libFile.getAbsolutePath()).append(';');
        }

        File openfireRoot = pluginDirectory.getParentFile().getParentFile().getParentFile();
        File openfireLib = new File(openfireRoot, "target//lib");

        classpath.append(openfireLib.getAbsolutePath()).append("//servlet-api.jar;");
        classpath.append(openfireLib.getAbsolutePath()).append("//openfire.jar;");
        classpath.append(openfireLib.getAbsolutePath()).append("//jasper-compiler.jar;");
        classpath.append(openfireLib.getAbsolutePath()).append("//jasper-runtime.jar;");

        if (pluginEnv.getClassesDir() != null) {
            classpath.append(pluginEnv.getClassesDir().getAbsolutePath()).append(';');
        }
        return classpath.toString();
    }
}
