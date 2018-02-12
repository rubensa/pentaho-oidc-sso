package com.arena.sso;

import com.arena.sso.oidc.OAuthUserDetailsService;
import org.pentaho.platform.api.engine.IPentahoSession;
import org.pentaho.platform.api.engine.security.userroledao.IUserRoleDao;
import org.pentaho.platform.api.engine.security.userroledao.NotFoundException;
import org.pentaho.platform.api.mt.ITenant;
import org.pentaho.platform.api.mt.ITenantManager;
import org.pentaho.platform.engine.core.system.PentahoSessionHolder;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.engine.security.SecurityHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * Implements UserDetailsService for SSO authentication mechanism.
 * The approach is to wrap intended PentahoUserDetailsService adding user creation functionality for users signing-up
 * through integrated Identity Provider Service.
 * <p>
 * Normally, Pentaho Security denys user creation if authorized session does not belong to Admin user. To enable
 * "auto registration" it is required to reduce security here. So, UserRoleDao security interception should allow
 * <code>createUser, setPassword, setUserDescription</code> methods invocation.
 * See "SSO authentication" integration guide for details.
 */
public class SsoUserDetailsService implements OAuthUserDetailsService, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SsoUserDetailsService.class);
    //~ Instance fields ================================================================================================

    private ITenantManager tenantManager;
    
    private UserDetailsService pentahoUserDetailsService;

    private IUserRoleDao userRoleDao;

    private String[] roles;

    //~ Constructor ====================================================================================================

    public SsoUserDetailsService(UserDetailsService pentahoUserDetailsService) {
        this.pentahoUserDetailsService = pentahoUserDetailsService;
    }

    //~ Methods ========================================================================================================

    @Override
    public void afterPropertiesSet() {
        if(roles == null) {
            roles = new String[] {};
        }
    }
    
    @Override
    public UserDetails loadUser(String user, String[] roles) throws Exception
    {
        return loadUser(null, user, roles);
    }
    
    /**
     * {@inheritDoc}
     *
     * Creates new user if there is no one available in the repository by given user name.
     * Should be used only for SSO authentication.
     */
    @Override
    public UserDetails loadUser(String tenantId, String username, String[] roles) throws Exception
    {
        
        UserDetails user;
        ITenant tenant = null;
        
        try {
            //log.debug("Try get tenant with name: {}", tenantId);
            //tenant = SecurityHelper.getInstance().runAsSystem(() -> tenantManager.getTenant(tenantId));
            /*
            if (tenant == null){
                tenant = SecurityHelper.getInstance().runAsSystem(() ->tenantManager.createTenant(JcrTenantUtils.getTenant(), tenantId, "Administrator", tenantId + "Authenticated", tenantId + "Anonymous"));
            }
            */
            for (String role: roles)
            {
                if (userRoleDao.getRole(tenant, role) == null)
                {
                    SecurityHelper.getInstance().runAsSystem(() -> userRoleDao.createRole(tenant, role, "", new String[]{}));
                    log.info("Role {} created", role);
                }
            }
            SecurityHelper.getInstance().runAsSystem(() -> { userRoleDao.setUserRoles(tenant, username, roles); return null;} );
            user = pentahoUserDetailsService.loadUserByUsername(username);
        } catch (UsernameNotFoundException|NotFoundException e) {

            if ( userRoleDao == null ) {
                userRoleDao = PentahoSystem.get(IUserRoleDao.class, "userRoleDaoProxy", PentahoSessionHolder.getSession());
            }
            
            String password = PasswordGenerator.generate();
            SecurityHelper.getInstance().runAsSystem(() -> userRoleDao.createUser(tenant, username, password, "", roles ));
            user = pentahoUserDetailsService.loadUserByUsername(username);
        } catch (Exception ex)
        {
            log.error("Error while login user: ", ex);
            throw new RuntimeException(ex);
        }
        return user;
    }
    
    @Override
    public UserDetails loadUserByUsername(String user) throws UsernameNotFoundException
    {
        try
        {
            return loadUser(null, user, this.roles);
        }
        catch (Exception e)
        {
            throw new UsernameNotFoundException("User " + user + " not found.", e);
        }
    }
    
    public void setTenantManager(ITenantManager tenantManager)
    {
        this.tenantManager = tenantManager;
    }
    
    public void setUserRoleDao(IUserRoleDao userRoleDao) {
        this.userRoleDao = userRoleDao;
    }

    public void setRoles(String[] roles) {
        this.roles = roles;
    }
}
