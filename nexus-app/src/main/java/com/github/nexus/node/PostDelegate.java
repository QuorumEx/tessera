package com.github.nexus.node;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

public class PostDelegate {

    private Client client;

    public PostDelegate(final Client client){
        this.client = client;
    }

    public byte[] doPost(final String url, final String path, final byte[] data) {

        final Response response = client
            .target(url)
            .path(path)
            .request()
            .post(Entity.entity(data, MediaType.APPLICATION_OCTET_STREAM_TYPE));

        return response.readEntity(byte[].class);
    }

}
