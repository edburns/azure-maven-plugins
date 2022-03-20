/*
 * Copyright (c) Microsoft Corporation. All rights reserved.
 * Licensed under the MIT License. See License.txt in the project root for license information.
 */

package com.microsoft.azure.toolkit.lib.compute.virtualmachine;

import com.azure.resourcemanager.compute.ComputeManager;
import com.azure.resourcemanager.compute.models.VirtualMachine.DefinitionStages;
import com.azure.resourcemanager.network.models.NetworkInterface;
import com.microsoft.azure.toolkit.lib.Azure;
import com.microsoft.azure.toolkit.lib.common.bundle.AzureString;
import com.microsoft.azure.toolkit.lib.common.exception.AzureToolkitRuntimeException;
import com.microsoft.azure.toolkit.lib.common.messager.AzureMessager;
import com.microsoft.azure.toolkit.lib.common.messager.IAzureMessager;
import com.microsoft.azure.toolkit.lib.common.model.AbstractAzResource;
import com.microsoft.azure.toolkit.lib.common.model.AzResource;
import com.microsoft.azure.toolkit.lib.common.model.Region;
import com.microsoft.azure.toolkit.lib.common.operation.AzureOperation;
import com.microsoft.azure.toolkit.lib.common.utils.Utils;
import com.microsoft.azure.toolkit.lib.compute.virtualmachine.model.AuthenticationType;
import com.microsoft.azure.toolkit.lib.compute.virtualmachine.model.OperatingSystem;
import com.microsoft.azure.toolkit.lib.compute.virtualmachine.model.SpotConfig;
import com.microsoft.azure.toolkit.lib.network.AzureNetwork;
import com.microsoft.azure.toolkit.lib.network.networksecuritygroup.NetworkSecurityGroup;
import com.microsoft.azure.toolkit.lib.network.networksecuritygroup.NetworkSecurityGroupDraft;
import com.microsoft.azure.toolkit.lib.network.networksecuritygroup.SecurityRule;
import com.microsoft.azure.toolkit.lib.network.publicipaddress.PublicIpAddress;
import com.microsoft.azure.toolkit.lib.network.publicipaddress.PublicIpAddressDraft;
import com.microsoft.azure.toolkit.lib.network.virtualnetwork.Network;
import com.microsoft.azure.toolkit.lib.network.virtualnetwork.Subnet;
import com.microsoft.azure.toolkit.lib.network.virtualnetwork.NetworkDraft;
import com.microsoft.azure.toolkit.lib.storage.AzureStorageAccount;
import com.microsoft.azure.toolkit.lib.storage.model.StorageAccountConfig;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;

import static com.azure.resourcemanager.compute.models.VirtualMachineEvictionPolicyTypes.DEALLOCATE;
import static com.azure.resourcemanager.compute.models.VirtualMachineEvictionPolicyTypes.DELETE;
import static com.microsoft.azure.toolkit.lib.compute.virtualmachine.model.SpotConfig.EvictionPolicy.StopAndDeallocate;

@Getter
@Setter
public class VirtualMachineDraft extends VirtualMachine implements AzResource.Draft<VirtualMachine, com.azure.resourcemanager.compute.models.VirtualMachine> {
    @Nullable
    private final VirtualMachine origin;

    @Getter(AccessLevel.NONE)
    private Region region;
    @Getter(AccessLevel.NONE)
    private String adminUserName;
    @Nullable
    private VmImage image;
    @Nullable
    private Network network;
    @Nullable
    private Subnet subnet;
    @Nullable
    private PublicIpAddress ipAddress;
    @Nullable
    private NetworkSecurityGroup securityGroup;
    @Nullable
    private AuthenticationType authenticationType;
    @Nullable
    private String password;
    @Nullable
    private String sshKey;
    @Nullable
    private VmSize size;
    @Nullable
    private String availabilitySet;
    @Nullable
    private StorageAccountConfig storageAccount;
    @Nullable
    private SpotConfig spotConfig;

    VirtualMachineDraft(@Nonnull String name, @Nonnull String resourceGroupName, @Nonnull VirtualMachineModule module) {
        super(name, resourceGroupName, module);
        this.origin = null;
    }

    VirtualMachineDraft(@Nonnull VirtualMachine origin) {
        super(origin);
        this.origin = origin;
    }

    @Override
    public void reset() {
        this.region = null;
        this.adminUserName = null;
        this.image = null;
        this.network = null;
        this.subnet = null;
        this.ipAddress = null;
        this.securityGroup = null;
        this.authenticationType = null;
        this.password = null;
        this.sshKey = null;
        this.size = null;
        this.availabilitySet = null;
        this.storageAccount = null;
        this.spotConfig = null;
    }

    public VirtualMachineDraft withDefaultConfig() {
        this.setRegion(Region.US_CENTRAL);
        this.setImage(VmImage.UBUNTU_SERVER_18_04_LTS);
        this.setSize(VmSize.Standard_D2s_v3);
        final String subs = this.getSubscriptionId();
        final String rg = this.getResourceGroupName();

        final String networkName = String.format("network-%s", Utils.getTimestamp());
        final NetworkDraft networkDraft = Azure.az(AzureNetwork.class).virtualNetworks(subs).create(networkName, rg);
        final String ipAddressName = String.format("public-ip-%s", Utils.getTimestamp());
        final PublicIpAddressDraft ipAddressDraft = Azure.az(AzureNetwork.class).publicIpAddresses(subs).create(ipAddressName, rg);
        final String securityGroupName = String.format("security-group-%s", Utils.getTimestamp());
        final NetworkSecurityGroupDraft securityGroupDraft = Azure.az(AzureNetwork.class).networkSecurityGroups(subs).create(securityGroupName, rg);

        networkDraft.withDefaultConfig();
        securityGroupDraft.setSecurityRules(Collections.singletonList(SecurityRule.SSH_RULE));

        this.setNetwork(networkDraft);
        this.setIpAddress(ipAddressDraft);
        this.setSecurityGroup(securityGroupDraft);
        return this;
    }

    @Nonnull
    @Override
    @AzureOperation(
        name = "resource.create_resource.resource|type",
        params = {"this.getName()", "this.getResourceTypeName()"},
        type = AzureOperation.Type.SERVICE
    )
    public com.azure.resourcemanager.compute.models.VirtualMachine createResourceInAzure() {
        final ComputeManager manager = Objects.requireNonNull(this.getParent().getRemote());
        final String name = this.getName();
        //TODO: read from config because `VirtualMachine` doesn't have these methods
        final String availabilitySet = this.getAvailabilitySet();
        final StorageAccountConfig storageAccountConfig = this.getStorageAccount();
        final SpotConfig spotConfig = this.getSpotConfig();

        final NetworkInterface networkInterface = createNetworkInterface();
        final DefinitionStages.WithProximityPlacementGroup createVm = manager.virtualMachines()
            .define(this.getName())
            .withRegion(networkInterface.region().name())
            .withExistingResourceGroup(this.getResourceGroupName())
            .withExistingPrimaryNetworkInterface(networkInterface);
        final DefinitionStages.WithCreate withCreate = configureImage(createVm);
        Optional.ofNullable(availabilitySet).filter(StringUtils::isNotEmpty)
            .map(s -> manager.availabilitySets().getByResourceGroup(this.getResourceGroupName(), s))
            .ifPresent(withCreate::withExistingAvailabilitySet);
        Optional.ofNullable(storageAccountConfig).map(StorageAccountConfig::getId)
            .map(id -> Azure.az(AzureStorageAccount.class).account(id)).map(AbstractAzResource::getRemote)
            .ifPresent(withCreate::withExistingStorageAccount);
        Optional.ofNullable(spotConfig).map(c -> c.getPolicy() == StopAndDeallocate ? DEALLOCATE : DELETE)
            .ifPresent(p -> withCreate.withSpotPriority(p).withMaxPrice(spotConfig.getMaximumPrice()));
        try {
            final IAzureMessager messager = AzureMessager.getMessager();
            messager.info(AzureString.format("Start creating Virtual machine ({0})...", name));
            final com.azure.resourcemanager.compute.models.VirtualMachine vm = this.doModify(() -> withCreate.create(), Status.CREATING);
            messager.success(AzureString.format("Virtual machine ({0}) is successfully created", name));
            return Objects.requireNonNull(vm);
        } catch (Exception e) {
            // clean up resource once creation failed
            networkInterface.manager().networkInterfaces().deleteById(networkInterface.id());
            throw e;
        }
    }

    private NetworkInterface createNetworkInterface() {
        final String name = this.getName();
        final Region region = Objects.requireNonNull(this.getRegion(), "'region' is required to create a Virtual machine");
        //TODO: read from config because `VirtualMachine` doesn't have these methods
        final Subnet subnet = Objects.requireNonNull(this.getSubnet(), "'subnet' is required to create a Virtual machine");
        final Network network = Objects.requireNonNull(this.getNetwork(), "'network' is required to create a Virtual machine");
        final PublicIpAddress ipAddress = this.getIpAddress();
        final NetworkSecurityGroup securityGroup = this.getSecurityGroup();

        final ComputeManager manager = Objects.requireNonNull(this.getParent().getRemote());
        final NetworkInterface.DefinitionStages.WithCreate createNetworkInterface = manager.networkManager().networkInterfaces()
            .define(name + "-interface-" + Utils.getTimestamp())
            .withRegion(region.getName())
            .withExistingResourceGroup(this.getResourceGroupName())
            .withExistingPrimaryNetwork(network.getRemote())
            .withSubnet(subnet.getName())
            .withPrimaryPrivateIPAddressDynamic();
        Optional.ofNullable(ipAddress).map(AbstractAzResource::getRemote).ifPresent(r -> {
            if (!r.hasAssignedNetworkInterface()) {
                createNetworkInterface.withExistingPrimaryPublicIPAddress(r);
            } else {
                AzureMessager.getMessager().warning(AzureString.format("Can not assign public ip %s to vm %s, which has been assigned to %s",
                    ipAddress.getName(), name, r.getAssignedNetworkInterfaceIPConfiguration().name()));
            }
        });
        Optional.ofNullable(securityGroup).ifPresent(g -> createNetworkInterface.withExistingNetworkSecurityGroup(g.getRemote()));
        return createNetworkInterface.create();
    }

    private DefinitionStages.WithCreate configureImage(final DefinitionStages.WithProximityPlacementGroup withCreate) {
        //TODO: read from config because `VirtualMachine` doesn't have these methods
        final VmImage image = this.getImage();
        final String password = this.getPassword();
        final String userName = this.getAdminUserName();
        final VmSize size = this.getSize();
        final AuthenticationType authenticationType = this.getAuthenticationType();
        final String sshKey = this.getSshKey();

        if (image.getOperatingSystem() == OperatingSystem.Windows) {
            return withCreate.withSpecificWindowsImageVersion(image.getImageReference())
                .withAdminUsername(userName).withAdminPassword(password).withSize(size.getName());
        } else {
            final DefinitionStages.WithLinuxRootPasswordOrPublicKeyManagedOrUnmanaged withLinuxImage =
                withCreate.withSpecificLinuxImageVersion(image.getImageReference()).withRootUsername(userName);
            final DefinitionStages.WithLinuxCreateManagedOrUnmanaged withLinuxAuthentication = authenticationType == AuthenticationType.Password ?
                withLinuxImage.withRootPassword(password) : withLinuxImage.withSsh(sshKey);
            return withLinuxAuthentication.withSize(size.getName());
        }
    }

    @Nonnull
    @Override
    @AzureOperation(
        name = "resource.update_resource.resource|type",
        params = {"this.getName()", "this.getResourceTypeName()"},
        type = AzureOperation.Type.SERVICE
    )
    public com.azure.resourcemanager.compute.models.VirtualMachine updateResourceInAzure(@Nonnull com.azure.resourcemanager.compute.models.VirtualMachine origin) {
        throw new AzureToolkitRuntimeException("not supported");
    }

    @Nullable
    public Region getRegion() {
        return Optional.ofNullable(this.region).orElseGet(super::getRegion);
    }

    @Nullable
    public String getAdminUserName() {
        return Optional.ofNullable(this.adminUserName).filter(StringUtils::isNotBlank).orElseGet(super::getAdminUserName);
    }

    @Override
    public boolean isModified() {
        final boolean notModified =
            Objects.isNull(this.region) || Objects.equals(this.region, super.getRegion()) ||
                Objects.isNull(this.adminUserName) || Objects.equals(this.adminUserName, super.getAdminUserName()) ||
                Objects.isNull(this.getImage()) ||
                Objects.isNull(this.getNetwork()) ||
                Objects.isNull(this.getIpAddress()) ||
                Objects.isNull(this.getSecurityGroup()) ||
                Objects.isNull(this.getAuthenticationType()) ||
                Objects.isNull(this.getPassword()) ||
                Objects.isNull(this.getSshKey()) ||
                Objects.isNull(this.getSize()) ||
                Objects.isNull(this.getAvailabilitySet()) ||
                Objects.isNull(this.getStorageAccount()) ||
                Objects.isNull(this.getSpotConfig());
        return !notModified;
    }
}