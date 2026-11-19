package vangogh.music;

import vangogh.net.GeniusClient;

/** Alias for {@link GeniusMusicService}, matching the generic "Genius service" name. */
public class GeniusService extends GeniusMusicService {
    public GeniusService() { super(); }
    public GeniusService(GeniusClient client) { super(client); }
    public GeniusService(String accessToken) { super(accessToken); }
}
