package org.tmatesoft.svn.core;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.io.StreamCorruptedException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;
import java.util.StringTokenizer;
import org.tmatesoft.svn.core.internal.util.SVNEncodingUtil;
import org.tmatesoft.svn.core.internal.util.SVNHashMap;
import org.tmatesoft.svn.core.internal.util.SVNPathUtil;
import org.tmatesoft.svn.core.internal.wc.SVNErrorManager;
import org.tmatesoft.svn.core.internal.wc.SVNFileUtil;
import org.tmatesoft.svn.util.SVNLogType;

public class SVNURL
  implements Serializable
{
  private static final Map DEFAULT_PORTS = new SVNHashMap();
  private String myURL;
  private String myProtocol;
  private String myHost;
  private String myPath;
  private String myUserName;
  private int myPort;
  private String myEncodedPath;
  private boolean myIsDefaultPort;
  private static final long serialVersionUID = 1L;

  public static SVNURL create(String protocol, String userInfo, String host, int port, String path, boolean uriEncoded)
    throws SVNException
  {
    if (((host == null) && (!"file".equalsIgnoreCase(protocol))) || ((host != null) && (host.indexOf('@') >= 0))) {
      SVNErrorManager.error(SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Invalid host name ''{0}''", host), SVNLogType.DEFAULT);
    }
    path = path == null ? "/" : path;
    if (!uriEncoded)
      path = SVNEncodingUtil.uriEncode(path);
    else {
      path = SVNEncodingUtil.autoURIEncode(path);
    }
    if ((path.length() > 0) && (path.charAt(0) != '/')) {
      path = "/" + path;
    }
    if ((path.length() > 0) && (path.charAt(path.length() - 1) == '/')) {
      path = path.substring(0, path.length() - 1);
    }
    protocol = protocol == null ? "http" : protocol.toLowerCase();
    String errorMessage = null;
    if ((userInfo != null) && (userInfo.indexOf('/') >= 0))
      errorMessage = "Malformed URL: user info part could not include '/' symbol";
    else if ((host == null) && (!"file".equals(protocol)))
      errorMessage = "Malformed URL: host could not be NULL";
    else if ((!"file".equals(protocol)) && (host.indexOf('/') >= 0)) {
      errorMessage = "Malformed URL: host could not include '/' symbol";
    }
    if (errorMessage != null) {
      SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, errorMessage);
      SVNErrorManager.error(err, SVNLogType.DEFAULT);
    }
    String url = composeURL(protocol, userInfo, host, port, path);
    return new SVNURL(url, true);
  }

  @Deprecated
  public static SVNURL parseURIDecoded(String url)
    throws SVNException
  {
    return new SVNURL(url, false);
  }

  public static SVNURL parseURIEncoded(String url)
    throws SVNException
  {
    return new SVNURL(url, true);
  }

  public static SVNURL fromFile(File repositoryPath)
    throws SVNException
  {
    if (repositoryPath == null) {
      return null;
    }
    String path = repositoryPath.getAbsoluteFile().getAbsolutePath();
    String host = null;
    if (((SVNFileUtil.isWindows) && (path.startsWith("//"))) || (path.startsWith("\\\\")))
    {
      path = path.replace(File.separatorChar, '/');
      path = path.substring("//".length());
      int lastIndex = path.indexOf("/") > 0 ? path.indexOf("/") : path.length();
      host = path.substring(0, lastIndex);
      path = path.substring(host.length());
    } else {
      path = path.replace(File.separatorChar, '/');
    }
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    return create("file", null, host, -1, path, false);
  }

  public static int getDefaultPortNumber(String protocol)
  {
    if (protocol == null) {
      return -1;
    }
    protocol = protocol.toLowerCase();
    if ("file".equals(protocol))
      return -1;
    Integer dPort;
    synchronized (DEFAULT_PORTS) {
      dPort = (Integer)DEFAULT_PORTS.get(protocol);
    }
    if (dPort != null) {
      return dPort.intValue();
    }
    return -1;
  }

  public static void registerProtocol(String protocolName, int defaultPort)
  {
    if (protocolName != null)
      synchronized (DEFAULT_PORTS) {
        if (defaultPort >= 0)
          DEFAULT_PORTS.put(protocolName, new Integer(defaultPort));
        else
          DEFAULT_PORTS.remove(protocolName);
      }
  }

  private SVNURL(String url, boolean uriEncoded)
    throws SVNException
  {
    if (url == null) {
      SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "URL cannot be NULL");
      SVNErrorManager.error(err, SVNLogType.DEFAULT);
    }
    if (url.endsWith("/")) {
      url = url.substring(0, url.length() - 1);
    }
    int index = url.indexOf("://");
    if (index <= 0) {
      SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Malformed URL ''{0}''", url);
      SVNErrorManager.error(err, SVNLogType.DEFAULT);
    }
    this.myProtocol = url.substring(0, index);
    this.myProtocol = this.myProtocol.toLowerCase();
    synchronized (DEFAULT_PORTS) {
      if ((!DEFAULT_PORTS.containsKey(this.myProtocol)) && (!this.myProtocol.startsWith("svn+"))) {
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "URL protocol is not supported ''{0}''", url);
        SVNErrorManager.error(err, SVNLogType.DEFAULT);
      }
    }
    if ("file".equals(this.myProtocol)) {
      String normalizedPath = norlmalizeURLPath(url, url.substring("file://".length()));
      int slashInd = normalizedPath.indexOf('/');
      if ((slashInd == -1) && (normalizedPath.length() > 0))
      {
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.RA_ILLEGAL_URL, "Local URL ''{0}'' contains only a hostname, no path", url);
        SVNErrorManager.error(err, SVNLogType.DEFAULT);
      }

      this.myPath = (slashInd == -1 ? "" : normalizedPath.substring(slashInd));
      if (normalizedPath.equals(this.myPath))
        this.myHost = "";
      else {
        this.myHost = normalizedPath.substring(0, slashInd);
      }

      URL testURL = null;
      try {
        testURL = new URL(this.myProtocol + "://" + normalizedPath);
      } catch (MalformedURLException e) {
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Malformed URL: ''{0}'': {1}", new Object[] { url, e.getLocalizedMessage() });
        SVNErrorManager.error(err, e, SVNLogType.DEFAULT);
        return;
      }

      if (uriEncoded)
      {
        this.myPath = this.myPath.replace('\\', '/');

        this.myEncodedPath = SVNEncodingUtil.autoURIEncode(this.myPath);
        SVNEncodingUtil.assertURISafe(this.myEncodedPath);
        this.myPath = SVNEncodingUtil.uriDecode(this.myEncodedPath);
        if (!this.myPath.startsWith("/"))
          this.myPath = ("/" + this.myPath);
      }
      else {
        this.myEncodedPath = SVNEncodingUtil.uriEncode(this.myPath);
        this.myPath = this.myPath.replace('\\', '/');
        if (!this.myPath.startsWith("/")) {
          this.myPath = ("/" + this.myPath);
        }
      }
      this.myUserName = testURL.getUserInfo();
      this.myPort = testURL.getPort();
    } else { String testURL = "http" + url.substring(index);
      URL httpURL;
      try {
        httpURL = new URL(testURL);
      } catch (MalformedURLException e) {
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Malformed URL: ''{0}'': {1}", new Object[] { url, e.getLocalizedMessage() });
        SVNErrorManager.error(err, e, SVNLogType.DEFAULT);
        return;
      }
      this.myHost = httpURL.getHost();
      if ("".equals(this.myHost)) {
        SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "Malformed URL: ''{0}''", url);
        SVNErrorManager.error(err, SVNLogType.DEFAULT);
        return;
      }
      String httpPath = norlmalizeURLPath(url, getPath(httpURL));
      if (uriEncoded)
      {
        this.myEncodedPath = SVNEncodingUtil.autoURIEncode(httpPath);
        SVNEncodingUtil.assertURISafe(this.myEncodedPath);
        this.myPath = SVNEncodingUtil.uriDecode(this.myEncodedPath);
      }
      else {
        String originalPath = url.substring(index + "://".length());
        if (originalPath.indexOf("/") < 0)
          originalPath = "";
        else {
          originalPath = originalPath.substring(originalPath.indexOf("/") + 1);
        }
        this.myPath = originalPath;
        if (!this.myPath.startsWith("/")) {
          this.myPath = ("/" + this.myPath);
        }
        this.myEncodedPath = SVNEncodingUtil.uriEncode(this.myPath);
      }
      this.myUserName = httpURL.getUserInfo();
      this.myPort = httpURL.getPort();
      this.myIsDefaultPort = (this.myPort < 0);
      if (this.myPort < 0)
      {
        Integer defaultPort;
        synchronized (DEFAULT_PORTS) {
          defaultPort = (Integer)DEFAULT_PORTS.get(this.myProtocol);
        }
        this.myPort = (defaultPort != null ? defaultPort.intValue() : 0);
      }
    }

    if (this.myEncodedPath.equals("/")) {
      this.myEncodedPath = "";
      this.myPath = "";
    }

    if (this.myHost != null)
      this.myHost = this.myHost.toLowerCase();
  }

  public String getProtocol()
  {
    return this.myProtocol;
  }

  public String getHost()
  {
    return this.myHost;
  }

  public int getPort()
  {
    return this.myPort;
  }

  public boolean hasPort()
  {
    return !this.myIsDefaultPort;
  }

  public String getPath()
  {
    return this.myPath;
  }

  public String getURIEncodedPath()
  {
    return this.myEncodedPath;
  }

  public String getUserInfo()
  {
    return this.myUserName;
  }

  public String toString()
  {
    if (this.myURL == null) {
      this.myURL = composeURL(getProtocol(), getUserInfo(), getHost(), this.myIsDefaultPort ? -1 : getPort(), getURIEncodedPath());
    }
    return this.myURL;
  }

  public String toDecodedString()
  {
    return composeURL(getProtocol(), getUserInfo(), getHost(), this.myIsDefaultPort ? -1 : getPort(), getPath());
  }

  public SVNURL appendPath(String segment, boolean uriEncoded)
    throws SVNException
  {
    if ((segment == null) || ("".equals(segment))) {
      return this;
    }
    if (!uriEncoded)
      segment = SVNEncodingUtil.uriEncode(segment);
    else {
      segment = SVNEncodingUtil.autoURIEncode(segment);
    }
    String path = getURIEncodedPath();
    if ("".equals(path))
      path = "/" + segment;
    else {
      path = SVNPathUtil.append(path, segment);
    }
    String url = composeURL(getProtocol(), getUserInfo(), getHost(), this.myIsDefaultPort ? -1 : getPort(), path);
    return parseURIEncoded(url);
  }

  public SVNURL setPath(String path, boolean uriEncoded)
    throws SVNException
  {
    if ((path == null) || ("".equals(path))) {
      path = "/";
    }
    if (!uriEncoded)
      path = SVNEncodingUtil.uriEncode(path);
    else {
      path = SVNEncodingUtil.autoURIEncode(path);
    }
    String url = composeURL(getProtocol(), getUserInfo(), getHost(), this.myIsDefaultPort ? -1 : getPort(), path);
    return parseURIEncoded(url);
  }

  public SVNURL removePathTail()
    throws SVNException
  {
    String newPath = SVNPathUtil.removeTail(this.myPath);
    String url = composeURL(getProtocol(), getUserInfo(), getHost(), this.myIsDefaultPort ? -1 : getPort(), newPath);
    return parseURIDecoded(url);
  }

  public boolean equals(Object obj)
  {
    if ((obj == null) || (obj.getClass() != SVNURL.class)) {
      return false;
    }
    SVNURL url = (SVNURL)obj;
    boolean eq = (this.myProtocol.equals(url.myProtocol)) && (this.myPort == url.myPort) && (this.myHost.equals(url.myHost)) && (this.myPath.equals(url.myPath)) && (hasPort() == url.hasPort());

    if (this.myUserName == null)
      eq &= url.myUserName == null;
    else {
      eq &= this.myUserName.equals(url.myUserName);
    }
    return eq;
  }

  public int hashCode()
  {
    int code = this.myProtocol.hashCode() + this.myHost.hashCode() * 27 + this.myPath.hashCode() * 31 + this.myPort * 17;
    if (this.myUserName != null) {
      code += 37 * this.myUserName.hashCode();
    }
    return code;
  }

  private static String composeURL(String protocol, String userInfo, String host, int port, String path) {
    StringBuffer url = new StringBuffer();
    url.append(protocol);
    url.append("://");
    if (userInfo != null) {
      url.append(userInfo);
      url.append("@");
    }
    if (host != null) {
      url.append(host);
    }
    if (port >= 0) {
      url.append(":");
      url.append(port);
    }
    if ((path != null) && (!path.startsWith("/"))) {
      path = '/' + path;
    }
    if (("/".equals(path)) && (!"file".equals(protocol))) {
      path = "";
    }
    url.append(path);
    return url.toString();
  }

  private static String norlmalizeURLPath(String url, String path) throws SVNException {
    StringBuffer result = new StringBuffer(path.length());
    for (StringTokenizer tokens = new StringTokenizer(path, "/"); tokens.hasMoreTokens(); ) {
      String token = tokens.nextToken();
      if ((!"".equals(token)) && (!".".equals(token)))
      {
        if ("..".equals(token)) {
          SVNErrorMessage err = SVNErrorMessage.create(SVNErrorCode.BAD_URL, "URL ''{0}'' contains '..' element", url);
          SVNErrorManager.error(err, SVNLogType.DEFAULT);
        } else {
          result.append("/");
          result.append(token);
        }
      }
    }
    if ((!path.startsWith("/")) && (result.length() > 0)) {
      result = result.delete(0, 1);
    }
    return result.toString();
  }

  private static String getPath(URL url) {
    String path = url.getPath();
    String ref = url.getRef();
    if (ref != null) {
      if (path == null) {
        path = "";
      }
      path = path + '#' + ref;
    }
    return path;
  }

  private synchronized void writeObject(ObjectOutputStream s) throws IOException {
    s.writeUTF(toDecodedString());
  }

  private synchronized void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException
  {
    this.myEncodedPath = s.readUTF();
  }

  private Object readResolve() throws ObjectStreamException {
    try {
      return new SVNURL(this.myEncodedPath, false);
    } catch (SVNException e) {
      StreamCorruptedException x = new StreamCorruptedException("Failed to load SVNURL");
      x.initCause(e);
      throw x;
    }
  }

  static
  {
    DEFAULT_PORTS.put("svn", new Integer(3690));
    DEFAULT_PORTS.put("svn+ssh", new Integer(22));
    DEFAULT_PORTS.put("http", new Integer(80));
    DEFAULT_PORTS.put("https", new Integer(443));
    DEFAULT_PORTS.put("file", new Integer(0));
  }
}