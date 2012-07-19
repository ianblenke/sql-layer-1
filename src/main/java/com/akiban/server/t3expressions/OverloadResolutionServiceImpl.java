/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.t3expressions;

import com.akiban.server.error.AkibanInternalException;
import com.akiban.server.error.ServiceStartupException;
import com.akiban.server.service.Service;
import com.akiban.server.types3.service.FunctionRegistryImpl;
import com.google.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicReference;

public class OverloadResolutionServiceImpl implements OverloadResolutionService, Service<OverloadResolutionService> {

    @Override
    public OverloadResolver getResolver() {
        OverloadResolver result = resolverRef.get();
        if (result == null)
            throw new AkibanInternalException("resolver not set");
        return result;
    }

    @Override
    public OverloadResolutionService cast() {
        return this;
    }

    @Override
    public Class<OverloadResolutionService> castClass() {
        return OverloadResolutionService.class;
    }

    @Override
    public void start() {
        FunctionRegistryImpl finder;
        try {
            finder = new FunctionRegistryImpl();
        } catch (Exception e) {
            logger.error("while creating registry", e);
            throw new ServiceStartupException(getClass().getSimpleName());
        }
        OverloadResolver resolver = new OverloadResolver(t3Registry);
        if (!resolverRef.compareAndSet(null, resolver))
            logger.warn("tried to set resolver when one already existed");
    }

    @Override
    public void stop() {
        resolverRef.set(null);
    }

    @Override
    public void crash() {
        resolverRef.set(null);
    }

    @Inject
    public OverloadResolutionServiceImpl(T3RegistryService registry) {
        this.t3Registry = registry;
    }

    private final T3RegistryService t3Registry;
    private final AtomicReference<OverloadResolver> resolverRef = new AtomicReference<OverloadResolver>();
    private static final Logger logger = LoggerFactory.getLogger(OverloadResolutionServiceImpl.class);
}