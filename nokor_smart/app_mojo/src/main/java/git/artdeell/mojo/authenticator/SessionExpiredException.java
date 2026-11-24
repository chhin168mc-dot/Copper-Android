package git.artdeell.mojo.authenticator;

import git.artdeell.mojo.R;
import git.artdeell.mojo.utils.PresentableException;

/**
 * The server does not accept the saved refresh token anymore (it expired, was revoked, or the
 * password was changed). Nothing is wrong with the connection: the player just has to sign in again.
 */
public class SessionExpiredException extends PresentableException {
    private static final long serialVersionUID = 4152038805141706319L;

    private final AuthType mAuthType;

    public SessionExpiredException(AuthType authType) {
        super(R.string.login_session_expired);
        this.mAuthType = authType;
    }

    /** @return the kind of the account that has to sign in again */
    public AuthType getAuthType() {
        return mAuthType;
    }
}
