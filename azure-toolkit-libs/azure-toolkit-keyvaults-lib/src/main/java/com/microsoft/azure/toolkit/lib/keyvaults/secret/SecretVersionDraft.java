/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.keyvaults.secret;

import com.azure.security.keyvault.secrets.SecretAsyncClient;
import com.azure.security.keyvault.secrets.models.SecretProperties;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.annotation.Nonnull;
import java.util.Objects;
import java.util.Optional;

public class SecretVersionDraft extends SecretVersion
    implements AzResource.Draft<SecretVersion, SecretProperties> {

    @Getter
    private final SecretVersion origin;

    @Setter
    private Config config;

    protected SecretVersionDraft(@Nonnull SecretVersion origin) {
        super(origin);
        this.origin = origin;
    }


    @Override
    public void reset() {
        this.config = null;
    }

    @Nonnull
    @Override
    public SecretProperties createResourceInAzure() {
        throw new AzureToolkitRuntimeException("Not support update secret");
    }

    @Nonnull
    @Override
    public SecretProperties updateResourceInAzure(@Nonnull SecretProperties origin) {
        final SecretAsyncClient secretClient = Objects.requireNonNull(getKeyVault().getSecretClient());
        final Boolean isEnabled = ensureConfig().getEnabled();
        final boolean isModified = Objects.nonNull(isEnabled) && !Objects.equals(isEnabled, origin.isEnabled());
        if (isModified) {
            origin.setEnabled(isEnabled);
            return Objects.requireNonNull(secretClient.updateSecretProperties(origin).block(), "failed to update secret");
        }
        return origin;
    }

    @Override
    public boolean isModified() {
        return Objects.nonNull(config);
    }

    private Config ensureConfig() {
        return Optional.ofNullable(config).orElseGet(Config::new);
    }

    public void setEnabled(boolean b) {
        ensureConfig().setEnabled(b);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class Config {
        private Boolean enabled;
    }
}

