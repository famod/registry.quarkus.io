package io.quarkus.registry.app;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;

import io.quarkus.cache.CacheResult;
import io.quarkus.maven.ArtifactCoords;
import io.quarkus.registry.app.maven.MavenConfig;
import io.quarkus.registry.app.model.Category;
import io.quarkus.registry.app.model.ExtensionRelease;
import io.quarkus.registry.app.model.ExtensionReleaseCompatibility;
import io.quarkus.registry.app.model.Platform;
import io.quarkus.registry.app.model.PlatformExtension;
import io.quarkus.registry.app.model.PlatformRelease;
import io.quarkus.registry.app.model.PlatformStream;
import io.quarkus.registry.app.model.mapper.PlatformMapper;
import io.quarkus.registry.catalog.Extension;
import io.quarkus.registry.catalog.ExtensionCatalog;
import io.quarkus.registry.catalog.ExtensionOrigin;
import io.quarkus.registry.catalog.PlatformCatalog;
import io.quarkus.registry.catalog.json.JsonExtension;
import io.quarkus.registry.catalog.json.JsonExtensionCatalog;
import io.quarkus.registry.catalog.json.JsonPlatform;
import io.quarkus.registry.catalog.json.JsonPlatformCatalog;
import io.quarkus.registry.catalog.json.JsonPlatformStream;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * This class will query the database for the requested information
 */
@ApplicationScoped
@Path("/client")
@Tag(name = "Client", description = "Client related services")
public class DatabaseRegistryClient {

    @Inject
    PlatformMapper platformMapper;

    @GET
    @Path("platforms")
    @CacheResult(cacheName = CacheNames.PLATFORMS)
    public PlatformCatalog resolvePlatforms() {
        JsonPlatformCatalog catalog = new JsonPlatformCatalog();
        List<PlatformRelease> platformReleases = PlatformRelease.findLatest();
        for (PlatformRelease platformRelease : platformReleases) {
            PlatformStream platformStream = platformRelease.platformStream;
            Platform platform = platformStream.platform;

            JsonPlatformStream jsonPlatformStream = platformMapper.toJsonPlatformStream(platformStream);
            jsonPlatformStream.addRelease(platformMapper.toJsonPlatformRelease(platformRelease));

            JsonPlatform jsonPlatform = platformMapper.toJsonPlatform(platform);
            jsonPlatform.addStream(jsonPlatformStream);

            catalog.addPlatform(jsonPlatform);
        }
        return catalog;
    }

    @GET
    @Path("non-platform-extensions")
    @CacheResult(cacheName = CacheNames.NON_PLATFORM_EXTENSIONS)
    public ExtensionCatalog resolveNonPlatformExtensions(@QueryParam("v") String quarkusVersion) {
        if (quarkusVersion == null) {
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }
        String id = new ArtifactCoords(MavenConfig.NON_PLATFORM_EXTENSION_COORDS.getGroupId(),
                                       MavenConfig.NON_PLATFORM_EXTENSION_COORDS.getArtifactId(),
                                       quarkusVersion,
                                       MavenConfig.NON_PLATFORM_EXTENSION_COORDS.getType(),
                                       MavenConfig.NON_PLATFORM_EXTENSION_COORDS.getVersion()).toString();

        final JsonExtensionCatalog catalog = new JsonExtensionCatalog();
        catalog.setId(id);
        catalog.setBom(ArtifactCoords.pom("io.quarkus", "quarkus-bom", quarkusVersion));
        List<ExtensionRelease> nonPlatformExtensions = ExtensionRelease.findNonPlatformExtensions(quarkusVersion);
        Map<Long, Boolean> compatiblityMap = ExtensionReleaseCompatibility.findCompatibleMap(quarkusVersion);
        for (ExtensionRelease extensionRelease : nonPlatformExtensions) {
            JsonExtension extension = toJsonExtension(extensionRelease, catalog);
            // Add compatibility info
            Boolean compatibility;

            String extensionQuarkusCore = (String) extension.getMetadata().get(Extension.MD_BUILT_WITH_QUARKUS_CORE);
            // Some extensions were published using the full GAV
            if (extensionQuarkusCore != null && extensionQuarkusCore.contains(":")) {
                try {
                    extensionQuarkusCore = ArtifactCoords.fromString(extensionQuarkusCore).getVersion();
                } catch (IllegalArgumentException iae) {
                    // ignore
                }
            }

            // If the requested quarkus version matches the quarkus core built, just assume it's compatible
            if (quarkusVersion.equals(extensionQuarkusCore)) {
                compatibility = Boolean.TRUE;
            } else {
                compatibility = compatiblityMap.get(extensionRelease.id);
            }
            extension.getMetadata().put("quarkus-core-compatibility", CoreCompatibility.parse(compatibility));
            catalog.addExtension(extension);
        }
        // Add all categories
        List<Category> categories = Category.listAll();
        categories.stream().map(platformMapper::toJsonCategory).forEach(catalog::addCategory);
        return catalog;
    }

    private JsonExtension toJsonExtension(PlatformExtension platformExtension, ExtensionOrigin extensionOrigin) {
        JsonExtension e = toJsonExtension(platformExtension.extensionRelease, extensionOrigin);
        e.setMetadata(platformExtension.metadata);
        return e;
    }

    private JsonExtension toJsonExtension(ExtensionRelease extensionRelease, ExtensionOrigin extensionOrigin) {
        JsonExtension e = new JsonExtension();
        e.setGroupId(extensionRelease.extension.groupId);
        e.setArtifactId(extensionRelease.extension.artifactId);
        e.setVersion(extensionRelease.version);
        e.setName(extensionRelease.extension.name);
        e.setDescription(extensionRelease.extension.description);
        e.setOrigins(Collections.singletonList(extensionOrigin));
        e.setMetadata(extensionRelease.metadata);
        return e;
    }

    private enum CoreCompatibility {
        UNKNOWN,
        COMPATIBLE,
        INCOMPATIBLE;

        public static CoreCompatibility parse(Boolean compatibility) {
            if (compatibility == null) {
                return UNKNOWN;
            } else {
                return compatibility ? COMPATIBLE : INCOMPATIBLE;
            }
        }
    }
}
