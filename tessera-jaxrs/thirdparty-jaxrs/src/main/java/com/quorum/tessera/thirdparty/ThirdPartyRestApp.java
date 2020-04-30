package com.quorum.tessera.thirdparty;

import com.quorum.tessera.api.common.RawTransactionResource;
import com.quorum.tessera.api.filter.IPWhitelistFilter;
import com.quorum.tessera.app.TesseraRestApplication;
import com.quorum.tessera.config.AppType;
import com.quorum.tessera.config.Config;
import com.quorum.tessera.core.api.ServiceFactory;
import com.quorum.tessera.partyinfo.PartyInfoService;
import com.quorum.tessera.partyinfo.PartyInfoServiceFactory;
import com.quorum.tessera.transaction.TransactionManager;
import io.swagger.annotations.Api;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.ws.rs.ApplicationPath;

/** The third party API */
@Api
@ApplicationPath("/")
public class ThirdPartyRestApp extends TesseraRestApplication {

    private final PartyInfoService partyInfoService;

    private final TransactionManager transactionManager;

    public ThirdPartyRestApp() {
        final ServiceFactory serviceFactory = ServiceFactory.create();

        Config config = serviceFactory.config();

        this.partyInfoService = PartyInfoServiceFactory.create(config).partyInfoService();
        this.transactionManager = TransactionManager.create(config);
    }

    @Override
    public Set<Object> getSingletons() {

        final IPWhitelistFilter iPWhitelistFilter = new IPWhitelistFilter();
        final RawTransactionResource rawTransactionResource = new RawTransactionResource(transactionManager);
        final PartyInfoResource partyInfoResource = new PartyInfoResource(partyInfoService);
        final KeyResource keyResource = new KeyResource();

        return Stream.of(iPWhitelistFilter, rawTransactionResource, partyInfoResource, keyResource)
                .collect(Collectors.toSet());
    }

    @Override
    public AppType getAppType() {
        return AppType.THIRD_PARTY;
    }
}
