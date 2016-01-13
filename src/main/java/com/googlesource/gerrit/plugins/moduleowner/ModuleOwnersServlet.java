package com.googlesource.gerrit.plugins.moduleowner;

import com.google.gerrit.extensions.annotations.PluginCanonicalWebUrl;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

@Singleton
class ModuleOwnersServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ModuleOwnersServlet.class);

    private final Provider<CurrentUser> userProvider;
    private final ModuleOwnerConfigCache configCache;

    @Inject
    public ModuleOwnersServlet(@PluginName String pluginName,
                               @PluginCanonicalWebUrl String url,
                               Provider<CurrentUser> userProvider,
                               ModuleOwnerConfigCache configCache) {
        this.userProvider = userProvider;
        this.configCache = configCache;

        log.info(String.format("'%s' at url %s", pluginName, url));
    }

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse rsp)
            throws IOException, ServletException {

        String project = req.getPathInfo();
        final String reply;
        if (canView() && project != null) {
            // Strip initial '/'
            project = project.substring(1);
            ModuleOwnerConfig config =
                    configCache.get(Project.NameKey.parse(project));
            if (config != null) {
                reply = generateReply(config);
            } else {
                reply = "<p>Project " + project + " not found</p>";
            }
        } else {
            reply = "<p>Error</p>";
        }

        rsp.setContentType("text/html");
        rsp.setCharacterEncoding("UTF-8");
        final Writer out = rsp.getWriter();
        out.write("<html>");
        out.write("<body>");
        out.write("<h2>Module Owner</h2>");
        out.write(reply);
        out.write("</body>");
        out.write("</html>");
        out.close();
    }

    private String generateReply(ModuleOwnerConfig config) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Account, List<String>> entry : config.getPatternMap().entrySet()) {
            sb.append("<h3>");
            sb.append(entry.getKey().getFullName());
            sb.append("</h3>");
            sb.append("<ul>");
            for (String pattern : entry.getValue()) {
                sb.append("<li>");
                sb.append(pattern);
                sb.append("</li>");
            }
            sb.append("</ul>\n");
        }
        return sb.toString();
    }

    private boolean canView() {
        //TODO we just check if a user is logged in for now, we might want a better method
        return userProvider.get().isIdentifiedUser();
    }
}
