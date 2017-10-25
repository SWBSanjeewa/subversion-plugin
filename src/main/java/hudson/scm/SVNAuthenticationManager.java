package hudson.scm;

import java.io.File;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.auth.SVNAuthentication;
import org.tmatesoft.svn.core.internal.wc.DefaultSVNAuthenticationManager;
import org.tmatesoft.svn.core.wc.SVNWCUtil;

public class SVNAuthenticationManager extends DefaultSVNAuthenticationManager
{
  public SVNAuthenticationManager(File configDir, String userName, String password)
  {
    super(configDir, 
      SVNWCUtil.createDefaultOptions(configDir, true)
      .isAuthStorageEnabled(), userName, password);
  }

  
  public SVNAuthentication getFirstAuthentication(String kind, String realm, SVNURL url)
    throws SVNException
  {
    return super.getAuthenticationProvider().requestClientAuthentication(kind, url, realm, null, null, false);
  }
}