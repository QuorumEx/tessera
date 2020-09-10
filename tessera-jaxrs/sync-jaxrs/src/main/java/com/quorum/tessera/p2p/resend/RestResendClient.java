package com.quorum.tessera.p2p.resend;

import com.quorum.tessera.p2p.recovery.PushBatchRequest;
import com.quorum.tessera.p2p.recovery.ResendBatchRequest;
import com.quorum.tessera.p2p.recovery.ResendBatchResponse;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Objects;

public class RestResendClient implements ResendClient {

    private final Client client;

    public RestResendClient(final Client client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public boolean makeResendRequest(final String targetUrl, final ResendRequest request) {
        final Entity<ResendRequest> outboundEntity = Entity.entity(request, MediaType.APPLICATION_JSON);

        try (Response response = client.target(targetUrl).path("/resend").request().post(outboundEntity)) {
            return Response.Status.OK.getStatusCode() == response.getStatus();
        }
    }

    //    @Override
    //    public boolean pushBatch(String targetUrl, PushBatchRequest pushBatchRequest) {
    //
    //        final Response response =
    //            client.target(targetUrl)
    //                .path("/pushBatch")
    //                .request()
    //                .post(Entity.entity(pushBatchRequest, MediaType.APPLICATION_JSON));
    //
    //        return Response.Status.OK.getStatusCode() == response.getStatus();
    //    }
    //
    //    @Override
    //    public ResendBatchResponse makeBatchResendRequest(String targetUrl, ResendBatchRequest request) {
    //        final Response response =
    //            client.target(targetUrl)
    //                .path("/resendBatch")
    //                .request()
    //                .post(Entity.entity(request, MediaType.APPLICATION_JSON));
    //
    //        if (Response.Status.OK.getStatusCode() == response.getStatus()) {
    //            return response.readEntity(ResendBatchResponse.class);
    //        }
    //
    //        return null;
    //    }

}
