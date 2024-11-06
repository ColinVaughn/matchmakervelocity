package org.texbobcat.matchmakervelocity;

// PlayerDisconnectListener.java
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.proxy.Player;

public class PlayerDisconnectListener {
    private final MatchMaker matchmaker;

    public PlayerDisconnectListener(MatchMaker matchmaker) {
        this.matchmaker = matchmaker;
    }

    @Subscribe
    public void onPlayerDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        matchmaker.handlePlayerDisconnection(player); // Call method to handle player disconnection
    }
}
