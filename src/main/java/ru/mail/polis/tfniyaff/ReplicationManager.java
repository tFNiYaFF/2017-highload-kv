package ru.mail.polis.tfniyaff;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;

import static ru.mail.polis.tfniyaff.Status.*;

/**
 * Created by Никита on 05.11.2017.
 */
public class ReplicationManager {
    private Set<String> topology;
    private int ack;
    private int from;
    private String query;
    private String method;
    private String master;
    private byte[] body;

    public ReplicationManager(Set<String> topology, int ack, int from, String query, String method, String master, byte[] body) {
        this.method = method;
        this.query = query;
        this.topology = topology;
        this.ack = ack;
        this.master = master;
        this.body = body;
        this.from = from;
    }

    public Status replication() throws IOException {
        int successReplication = 1;
        int connections = 1;
        int failedConnections = 0;
        for (String host : topology) {
            if (connections == from) break;
            host = host.replace("localhost", "127.0.0.1");
            if (!host.equals(master)) {
                HttpURLConnection testConnection = (HttpURLConnection) new URL(host + "/v0/status").openConnection();
                testConnection.setRequestMethod("GET");
                testConnection.setConnectTimeout(100);
                int testCode = 0;
                try {
                    testCode = testConnection.getResponseCode();
                } catch (IOException e) {
                    failedConnections++;
                    if (topology.size() - failedConnections < ack) {
                        return REPLICATION_FAILED;
                    }
                    if (method.equalsIgnoreCase("PUT")) {
                        try {
                            Files.write(Paths.get("repair"), (host + "\n" + query + "\n" + Arrays.toString(body) + "\n").getBytes(), StandardOpenOption.APPEND);
                        } catch (IOException ex) {
                            ex.printStackTrace();
                        }
                    }
                    continue;
                }
                if (testCode == 200) {
                    int code = 0;
                    connections++;
                    HttpURLConnection workConnection = (HttpURLConnection) new URL(host + "/v0/entity?" + query).openConnection();
                    workConnection.setRequestMethod(method);
                    workConnection.setRequestProperty("Replication", "slave");
                    workConnection.setConnectTimeout(100);
                    if (body != null) {
                        workConnection.setDoOutput(true);
                        try(DataOutputStream wr = new DataOutputStream(workConnection.getOutputStream())) {
                            wr.write(body);
                            wr.flush();
                        }
                    }
                    try {
                        code = workConnection.getResponseCode();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (code == 404) {
                        return REPLICATION_SERVER_FILE_NOT_FOUND;
                    }
                    successReplication++;
                }
            }
        }
        if (successReplication >= ack) {
            return REPLICATION_SUCCESS;
        }
        return REPLICATION_FAILED;
    }
}
