package io.quarkus.registry.app.maven;

import java.io.IOException;
import java.io.StringWriter;
import java.util.List;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import io.quarkus.cache.CacheResult;
import io.quarkus.maven.ArtifactCoords;
import io.quarkus.registry.app.CacheNames;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.logging.Logger;
import org.sonatype.nexus.repository.metadata.model.RepositoryMetadata;
import org.sonatype.nexus.repository.metadata.model.io.xpp3.RepositoryMetadataXpp3Writer;

/**
 * Exposes a Maven resource for our tooling
 */
@Path("/maven")
@Tag(name = "Client", description = "Client related services")
public class MavenResource {

    private static final Logger log = Logger.getLogger(MavenResource.class);

    @Inject
    PomContentProvider pomContentProvider;

    @Inject
    MetadataContentProvider metadataContentProvider;

    @Inject
    RegistryDescriptorContentProvider registryDescriptorContentProvider;

    @Inject
    PlatformsContentProvider platformsContentProvider;

    @Inject
    NonPlatformExtensionsContentProvider nonPlatformExtensionsContentProvider;

    private ArtifactContentProvider[] getContentProviders() {
        return new ArtifactContentProvider[] {
                pomContentProvider,
                metadataContentProvider,
                registryDescriptorContentProvider,
                platformsContentProvider,
                nonPlatformExtensionsContentProvider
        };
    }

    @GET
    @Path("{path:.+}")
    public Response handleArtifactRequest(
            @PathParam("path") List<PathSegment> pathSegments,
            @Context UriInfo uriInfo) {
        ArtifactCoords artifactCoords;
        try {
            artifactCoords = ArtifactParser.parseCoords(pathSegments);
        } catch (IllegalArgumentException iae) {
            log.debug("Error while parsing coords: " + iae.getMessage());
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        for (ArtifactContentProvider contentProvider : getContentProviders()) {
            if (contentProvider.supports(artifactCoords, uriInfo)) {
                try {
                    return contentProvider.provide(artifactCoords, uriInfo);
                } catch (WebApplicationException wae) {
                    throw wae;
                } catch (Exception e) {
                    log.error("Error while providing content", e);
                    return Response.serverError().build();
                }
            }
        }
        log.debugf("Not found: %s", uriInfo.getPath());
        return Response.status(Response.Status.NOT_FOUND).build();
    }
}
