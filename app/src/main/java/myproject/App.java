package myproject;

import com.pulumi.Context;
import com.pulumi.Pulumi;
import com.pulumi.azurenative.appconfiguration.*;
import com.pulumi.azurenative.appconfiguration.inputs.ListConfigurationStoreKeysArgs;
import com.pulumi.azurenative.appconfiguration.inputs.SkuArgs;
import com.pulumi.azurenative.containerservice.ManagedClusterArgs;
import com.pulumi.azurenative.containerservice.ManagedCluster;
import com.pulumi.azurenative.containerservice.ContainerserviceFunctions;
import com.pulumi.azurenative.containerservice.enums.*;
import com.pulumi.azurenative.containerservice.inputs.*;

import com.pulumi.core.Output;
import com.pulumi.kubernetes.Provider;
import com.pulumi.kubernetes.ProviderArgs;
import com.pulumi.kubernetes.core_v1.Namespace;
import com.pulumi.kubernetes.core_v1.NamespaceArgs;
import com.pulumi.kubernetes.meta_v1.inputs.ObjectMetaArgs;
import com.pulumi.resources.CustomResourceOptions;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class App {
  // standard main function
  public static void main(String[] args) {
    // Run a function in Pulumi deployment context
    Pulumi.run(App::stack);
  }

  /**
   * Deploys a
   * - Azure Configuration Store
   * - Azure Kubernetes Service cluster
   * - Kubernetes Namespace
   *
   * Note to self: Run pulumi up right now since it could take a while
   * @param ctx
   */
  public static void stack(Context ctx) {
    // you can store resources to variables and do normal Java stuff.

    final var configStore =
        // We follow the Object Oriented model and allow you to "construct resources"
        // in an idiomatic and familiar manner
        new ConfigurationStore(
            // This name will allow pulumi to track your resources across runs
            "config-store-1",
            // Configure your cloud resource
            // We use builder patterns to make it easier to configure cloud resources.
            ConfigurationStoreArgs.builder()
                // You can generally intellisense your way through this.
                .sku(SkuArgs.builder()
                    .name("standard")
                    .build())
                .resourceGroupName("mspulumi")
                .build(),
            // These options tell the engine extra details about
            // how it should treat these resources
            // More details here: https://www.pulumi.com/docs/intro/concepts/resources/options/
            CustomResourceOptions.builder()
                .build());

    final var mycluster =
        new ManagedCluster(
            "cluster-1",
            ManagedClusterArgs.builder()
                .identity(
                    ManagedClusterIdentityArgs.builder()
                        .type(ResourceIdentityType.SystemAssigned)
                        .build())
                .servicePrincipalProfile(
                    ManagedClusterServicePrincipalProfileArgs.builder().clientId("msi").build())
                .agentPoolProfiles(
                    ManagedClusterAgentPoolProfileArgs.builder()
                        .name("pool1")
                        .count(1)
                        .osType(OSType.Linux)
                        .osSKU(OSSKU.Ubuntu)
                        .vmSize("standard_a2_v2")
                        .mode(AgentPoolMode.System)
                        .build())
                .resourceGroupName("mspulumi")
                .dnsPrefix("pulumi-demo-1")
                .build(),
            CustomResourceOptions.builder().protect(true).build());

    // We want to get the kubeconfig
    final var kubeconfig = getKubeconfig(mycluster);

    final var opts =
        CustomResourceOptions.builder()
            // We need to create a provider
            // Providers are like clients for SDKs
            // They tell us how to connect to the provider
            .provider(new Provider("k8sProvider", ProviderArgs.builder()
                    // We need the kubeconfig to connect to the cluster
                    .kubeconfig(kubeconfig)
                    .build()))
            .build();

    // Finally create the namespace
    final var namespace =
        new Namespace(
            "dev-ns",
            NamespaceArgs.builder()
                .metadata(ObjectMetaArgs.builder().name("apps-namespace").build())
                .build(),
            opts); // We pass the options here with the information about the provider

    // We want to publish this information as stack outputs
    // Our app-dev pulumi stack will use these values to use the resources we deployed
    ctx.export("configStoreConnectionString", configStore.name().applyValue(name ->
        AppconfigurationFunctions.listConfigurationStoreKeys(
            ListConfigurationStoreKeysArgs.builder()
                .configStoreName(name)
                .resourceGroupName("mspulumi")
                .build()).join().value().get(0).connectionString()).asSecret());
    ctx.export("kubeconfig", kubeconfig);
    ctx.export("namespace", namespace.getId());
  }

  private static Output<String> getKubeconfig(ManagedCluster mycluster) {
    // We have this model that involves a type called Output
    // it lets you be able to create dependencies between resources
    final var clusterCreds =
        mycluster.name().applyValue(name ->
                // com.pulumi.{provider}.pkg.PkgFunctions has data source methods
                // that can allow you to query your cloud for info
                ContainerserviceFunctions.listManagedClusterUserCredentials(
                        ListManagedClusterUserCredentialsArgs.builder()
                            .resourceName(name)
                            .resourceGroupName("mspulumi")
                            .build())
                    .join());
    final var kubeconfig =
        clusterCreds
            .applyValue(creds -> creds.kubeconfigs().get(0).value())
            .applyValue(val -> Base64.getDecoder().decode(val))
            .applyValue(data -> new String(data, StandardCharsets.UTF_8))
            .asSecret();

    return kubeconfig;
  }
}
