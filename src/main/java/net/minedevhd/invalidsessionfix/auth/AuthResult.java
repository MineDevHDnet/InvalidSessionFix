package net.minedevhd.invalidsessionfix.auth;

public final class AuthResult {
    public String microsoftAccessToken;
    public String microsoftRefreshToken;
    public long microsoftExpiresIn;

    public String minecraftAccessToken;
    public long minecraftExpiresIn;

    public String profileName;
    public String profileId;
}
