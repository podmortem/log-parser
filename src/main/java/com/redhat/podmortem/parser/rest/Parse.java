package com.redhat.podmortem.parser.rest;

import com.redhat.podmortem.common.model.analysis.AnalysisResult;
import com.redhat.podmortem.common.model.kube.podmortem.PodFailureData;
import com.redhat.podmortem.parser.service.AnalysisService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST API controller for pod failure log parsing and analysis.
 *
 * <p>Provides endpoints for processing pod failure data and extracting meaningful patterns from
 * container logs. Orchestrates the complete log analysis pipeline from raw log data to structured
 * analysis results.
 */
@Path("/parse")
public class Parse {

    private static final Logger log = LoggerFactory.getLogger(Parse.class);

    @Inject AnalysisService analysisService;

    /**
     * Parses and analyzes pod failure logs to identify failure patterns.
     *
     * <p>Takes complete pod failure data including pod specification, logs, and events, then
     * applies pattern matching algorithms to identify the root cause and severity of the failure.
     * Returns structured analysis results with matched patterns, confidence scores, and contextual
     * information.
     *
     * @param data the complete pod failure data containing logs and metadata
     * @return an HTTP response with structured analysis results or error information
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response parseLogs(PodFailureData data) {
        if (data == null || data.getPod() == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid PodFailureData provided\"}")
                    .build();
        }

        log.info("Received analysis request for pod: {}", data.getPod().getMetadata().getName());

        AnalysisResult result = analysisService.analyze(data);

        log.info(
                "Analysis complete for pod: {}. Found {} significant events.",
                data.getPod().getMetadata().getName(),
                result.getSummary().getSignificantEvents());

        return Response.ok(result).build();
    }
}
