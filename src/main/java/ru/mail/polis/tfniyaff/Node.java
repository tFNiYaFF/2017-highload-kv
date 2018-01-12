package ru.mail.polis.tfniyaff;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import ru.mail.polis.KVService;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;

import static ru.mail.polis.tfniyaff.Status.REPLICATION_FAILED;
import static ru.mail.polis.tfniyaff.Status.REPLICATION_SUCCESS;

/**
 * Created by Никита on 08.10.2017.
 */
public class Node implements KVService {

    private HttpServer server;
    private File data;
    private Set<String> topology;
    private String delimiter = File.separator;

    public Node(int port, File data, Set<String> topology) throws IOException {
        this.topology = topology;
        this.data = data;
        server = HttpServer.create();
        server.bind(new InetSocketAddress(port), 1);
        server.createContext("/v0/entity", new RequestHandler());
        server.createContext("/v0/status", new StatusHandler());
        checkRepair(port);
    }

    private void checkRepair(int port) throws IOException {
        File file = new File("repair");
        String s;
        StringBuilder newString = new StringBuilder();
        if (file.exists()) {
            try (BufferedReader bf = new BufferedReader(new FileReader("repair"))) {
                while ((s = bf.readLine()) != null) {
                    if (s.split(":").length > 2 && port == Integer.valueOf(s.split(":")[2])) {
                        try (BufferedOutputStream bs = new BufferedOutputStream(new FileOutputStream(new File(data.getAbsolutePath() + delimiter + bf.readLine().split("&")[0].split("=")[1])))) {
                            String line = bf.readLine();
                            line = line.replace('[', ' ');
                            line = line.replace(']', ' ');
                            line = line.replaceAll(" ", "");
                            String[] bArr = line.split(",");
                            byte[] body = new byte[bArr.length];
                            for (int i = 0; i < bArr.length; i++) {
                                body[i] = Byte.valueOf(bArr[i]);
                            }
                            bs.write(body);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        newString.append(s).append("\n");
                    }
                }
                bf.close();
            } catch (Exception e) {
                e.printStackTrace();
            }
            file.delete();
            file.createNewFile();
            Files.write(Paths.get("repair"), newString.toString().getBytes(), StandardOpenOption.APPEND);
        } else {
            file.createNewFile();
        }
    }


    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            sendHttpResponse(httpExchange, 200, "OK");
        }
    }

    private class RequestHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange httpExchange) throws IOException {
            boolean master = false;
            String requestMethod = httpExchange.getRequestMethod();
            String query = httpExchange.getRequestURI().getQuery();
            String id;
            String replicas;
            int ack = -1;
            int from = -1;
            if (!httpExchange.getRequestHeaders().containsKey("Replication")) {
                master = true;
            }
            if (query.contains("&")) {
                String[] parameters = query.split("&");
                id = parameters[0];
                replicas = parameters[1];
                if (id.split("=").length == 1) {
                    sendHttpResponse(httpExchange, 400, "Empty ID");
                    return;
                }
                id = id.split("=")[1];
                if (replicas.split("=").length == 1) {
                    sendHttpResponse(httpExchange, 400, "Empty replicas");
                    return;
                }
                replicas = replicas.split("=")[1];
                ack = Integer.valueOf(replicas.split("/")[0]);
                from = Integer.valueOf(replicas.split("/")[1]);
                if (ack > from || ack == 0 || from == 0) {
                    sendHttpResponse(httpExchange, 400, "Invalid parameters");
                    return;
                }
            } else {
                if (query.split("=").length == 1) {
                    sendHttpResponse(httpExchange, 400, "Empty ID");
                    return;
                }
                id = query.split("=")[1];
            }
            if (ack == -1 || from == -1) {
                ack = topology.size() / 2 + 1;
                from = topology.size();
            }
            File file = new File(data.getAbsolutePath() + delimiter + id);
            if (requestMethod.equalsIgnoreCase("GET")) {
                if (master) {
                    ReplicationManager rm = new ReplicationManager(topology, ack, from, query, "GET", "http:/" + httpExchange.getLocalAddress().toString(), null, id);
                    Status status = rm.replication();
                    if (status == REPLICATION_SUCCESS) {
                        if (!file.exists()) {
                            sendHttpResponse(httpExchange, 404, "Not found");
                            return;
                        }
                        sendHttpResponse(httpExchange, 200, file);
                    } else if (status == REPLICATION_FAILED) {
                        sendHttpResponse(httpExchange, 504, "Not Enough Replicas");
                    } else {
                        sendHttpResponse(httpExchange, 404, "Not found");
                    }
                } else {
                    if (!file.exists()) {
                        sendHttpResponse(httpExchange, 404, "Not found");
                        return;
                    }
                    sendHttpResponse(httpExchange, 200, "OK");
                }
            } else if (requestMethod.equalsIgnoreCase("PUT")) {
                if (!file.exists()) file.createNewFile();
                byte[] buffer = new byte[1024];
                try (InputStream is = httpExchange.getRequestBody()) {
                    try (BufferedOutputStream bs = new BufferedOutputStream(new FileOutputStream(file))) {
                        for (int n = is.read(buffer); n > 0; n = is.read(buffer)) bs.write(buffer);
                    }
                }
                if (master) {
                    ReplicationManager rm = new ReplicationManager(topology, ack, from, query, "PUT", "http:/" + httpExchange.getLocalAddress().toString(), buffer, id);
                    if (rm.replication() == REPLICATION_SUCCESS) {
                        sendHttpResponse(httpExchange, 201, "Created");
                    } else {
                        sendHttpResponse(httpExchange, 504, "Not Enough Replicas");
                    }
                } else {
                    sendHttpResponse(httpExchange, 201, "Created");
                }
            } else if (requestMethod.equalsIgnoreCase("DELETE")) {
                if (!file.exists() || file.delete()) {
                    if (master) {
                        ReplicationManager rm = new ReplicationManager(topology, ack, from, query, "DELETE", "http:/" + httpExchange.getLocalAddress().toString(), null, id);
                        if (rm.replication() == REPLICATION_SUCCESS) {
                            sendHttpResponse(httpExchange, 202, "Accepted");
                        } else {
                            sendHttpResponse(httpExchange, 504, "Not Enough Replicas");
                        }
                    } else {
                        sendHttpResponse(httpExchange, 202, "Accepted");
                    }
                } else {
                    throw new IOException();
                }
            } else {
                throw new IOException();
            }
        }
    }

    @Override
    public void start() {
        server.start();
    }

    @Override
    public void stop() {
        server.stop(1);
    }

    private void sendHttpResponse(HttpExchange httpExchange, int code, File file) throws IOException {
        httpExchange.sendResponseHeaders(code, file.length());
        OutputStream outputStream = httpExchange.getResponseBody();
        Files.copy(file.toPath(), outputStream);
        outputStream.close();
    }

    private void sendHttpResponse(HttpExchange httpExchange, int code, String response) throws IOException {
        httpExchange.sendResponseHeaders(code, response.getBytes().length);
        httpExchange.getResponseBody().write(response.getBytes());
        httpExchange.getResponseBody().close();
    }
}
