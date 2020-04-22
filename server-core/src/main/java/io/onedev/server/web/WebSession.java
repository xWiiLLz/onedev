package io.onedev.server.web;

import javax.annotation.Nullable;
import javax.servlet.http.HttpSession;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.apache.wicket.protocol.http.WicketServlet;
import org.apache.wicket.request.Request;

import io.onedev.server.OneDev;
import io.onedev.server.web.util.Cursor;

public class WebSession extends org.apache.wicket.protocol.http.WebSession {

	private static final long serialVersionUID = 1L;	
	
	private volatile Cursor issueCursor; 

	private volatile Cursor buildCursor; 
	
	private volatile Cursor pullRequestCursor; 
	
	public WebSession(Request request) {
		super(request);
	}

	public static WebSession get() {
		return (WebSession) org.apache.wicket.protocol.http.WebSession.get();
	}

	public void login(String userName, String password, boolean rememberMe) {
		Subject subject = SecurityUtils.getSubject();

		// Force a new session to prevent session fixation attack.
		// We have to invalidate via both Shiro and Wicket; otherwise it doesn't
		// work.
		subject.getSession().stop();
		WebSession.get().replaceSession(); 

		UsernamePasswordToken token;
		token = new UsernamePasswordToken(userName, password, rememberMe);
		
		subject.login(token);
	}
	
	public void logout() {
		SecurityUtils.getSubject().logout();
        WebSession session = WebSession.get();
        session.replaceSession();
	}
	
	@Nullable
	public Cursor getIssueCursor() {
		return issueCursor;
	}

	@Nullable
	public Cursor getBuildCursor() {
		return buildCursor;
	}
	
	@Nullable
	public Cursor getPullRequestCursor() {
		return pullRequestCursor;
	}
	
	public void setIssueCursor(@Nullable Cursor issueCursor) {
		this.issueCursor = issueCursor;
	}

	public void setBuildCursor(@Nullable Cursor buildCursor) {
		this.buildCursor = buildCursor;
	}
	
	public void setPullRequestCursor(@Nullable Cursor pullRequestCursor) {
		this.pullRequestCursor = pullRequestCursor;
	}
	
	public static WebSession from(HttpSession session) {
		String attributeName = "wicket:" + OneDev.getInstance(WicketServlet.class).getServletName() + ":session";
		return (WebSession) session.getAttribute(attributeName);		
	}
	
}
