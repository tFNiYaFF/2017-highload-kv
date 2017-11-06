package ru.mail.polis.tfniyaff;

import java.util.ArrayList;

/**
 * Created by Никита on 06.11.2017.
 */
public class PutRepair {
    static ArrayList<String> hosts = new ArrayList<>();
    static ArrayList<String> queries = new ArrayList<>();
    static ArrayList<byte[]> bodies = new ArrayList<>();

    public static void addHost(String host, String query, byte[] body){
        hosts.add(host);
        queries.add(query);
        bodies.add(body);
    }
}
