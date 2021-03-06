package team.gutterteam123.master.sync;

import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import team.gutterteam123.baselib.constants.FileConstants;
import team.gutterteam123.baselib.constants.PortConstants;
import team.gutterteam123.baselib.constants.TimeConstants;
import team.gutterteam123.baselib.tasks.TaskManager;
import team.gutterteam123.baselib.tasks.TimerTask;
import team.gutterteam123.baselib.util.NetUtil;
import team.gutterteam123.master.Master;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Sync {

    final Logger logger = LoggerFactory.getLogger(getClass());

    private List<String> addresses;
    private String currentBest;

    @Setter @Getter private SyncClient client;
    @Getter private SyncServer server;

    public Sync() {
        addresses = new ArrayList<>(Master.getInstance().getConfigHelper().collectCountryRoots());
        logger.info("Loaded " + addresses.size() + " roots!");

        currentBest = getBestRoot();
        logger.info("Best Root is {}", currentBest);
        if (currentBest.equals(NetUtil.getRemoteIp())) {
            logger.info("Optimal Primary master is this machine! Starting Server...");
            server = new SyncServer();
            server.setStart(() -> {
                logger.info("Sync Server is up! Starting Client...");
                client = new SyncClient(currentBest);
                client.start();
            });
            server.start();
        } else {
            logger.info("Starting Sync Client...");
            client = new SyncClient(currentBest);
            client.start();
        }

        TaskManager.getInstance().registerTask(new TimerTask(true, () -> {
            String best = getBestRoot();
            if (!best.equals(currentBest)) {
                logger.info("Best Primary Master switched from {} to {}", currentBest, best);
                if (currentBest.equals(NetUtil.getRemoteIp())) {
                    server.shutdown();
                }
                if (best.equals(NetUtil.getRemoteIp())) {
                    logger.info("Optimal Primary master is this machine");
                    server = new SyncServer();
                    server.setStart(() -> {
                        client = new SyncClient(best);
                        client.start();
                    });
                    server.start();
                } else {
                    client = new SyncClient(best);
                    client.start();
                }

                currentBest = best;

                addresses.sort(Comparator.comparingInt(String::hashCode));
                for (String root : addresses) {
                    if (root.hashCode() > currentBest.hashCode() && isOnline(root)) {
                        logger.info("Sending Destroy packet to {}", root);
                        new DestroyClient(root, best).start();
                    }
                }
            } else {
                logger.info("Sync has not changed in the last {} seconds", TimeConstants.getSYNC_UPDATE() / 1000);
            }
        }, TimeConstants.getSYNC_UPDATE(), TimeConstants.getSYNC_UPDATE()));
    }

    private String getBestRoot() {
        addresses.sort(Comparator.comparingInt(String::hashCode));
        for (String root : addresses) {
            if (isOnline(root)) {
                return root;
            }
        }
        return NetUtil.getRemoteIp();
    }

    private boolean isOnline(String host) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, PortConstants.getMASTER_SYNC()), TimeConstants.getSYNC_TIMEOUT());
            socket.close();
        } catch (IOException ex) {
            return false;
        }
        return true;
    }

}
