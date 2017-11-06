package ru.mail.polis.tfniyaff;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import org.apache.http.client.fluent.Request;
import java.net.URL;
import java.util.Set;

/**
 * Created by Никита on 05.11.2017.
 */
public class ReplicationManager{
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
    public int replication() throws IOException {
        int successReplication = 1;
        int connections = 1;
        int failedConnections = 0;
        int t = 0;
        for (String host : topology) {
            t++;
            if(connections==from) break;
            host = host.replace("localhost", "127.0.0.1");
            if (!host.equals(master)) {
                HttpURLConnection testConnection = (HttpURLConnection) new URL(host + "/v0/status").openConnection();
                testConnection.setRequestMethod("GET");
                int testCode = 0;
                try {
                    testCode = testConnection.getResponseCode();
                } catch (IOException e) {
                    failedConnections++;
                    if(topology.size()-failedConnections<ack){
                        return -1;
                    }
                    if(method.equalsIgnoreCase("PUT")){
                        PutRepair.addHost(host,query,body);
                    }
                    continue;
                }
                if (testCode == 200) {
                    int code = 0;
                    connections++;
                    HttpURLConnection workConnection = (HttpURLConnection) new URL(host + "/v0/entity?"+query).openConnection();
                    workConnection.setRequestMethod(method);
                    workConnection.setRequestProperty("Replication","slave");
                    if(body!=null) {
                        workConnection.setDoOutput(true);
                        DataOutputStream wr = new DataOutputStream(workConnection.getOutputStream());
                        wr.write(body);
                        wr.flush();
                        wr.close();
                    }
                    try {
                        code = workConnection.getResponseCode();
                    }
                    catch (Exception e){
                        e.printStackTrace();
                    }
                    if(code==404){
                        return 1;
                    }
                    successReplication++;
                }
            }
        }
        if (successReplication >= ack) {
            return 0;
        }
        return -1;
    }
}
