package tech.stackable.t2.api.cluster.controller;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import io.swagger.v3.oas.annotations.Operation;
import tech.stackable.t2.api.cluster.service.TerraformAnsibleClusterService;

@RestController
@RequestMapping("api/diy-cluster")
public class DiyClusterController {

    @Autowired
    private TerraformAnsibleClusterService clusterService;

    @PostMapping(consumes = { "application/json",
            "application/yaml" }, produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseBody
    @Operation(summary = "Get DIY cluster package", description = "Creates a DIY cluster package (ZIP)")
    public byte[] createCluster(@RequestBody(required = true) String clusterDefinition) {
        return clusterService.createDiyCluster(clusterDefinition(clusterDefinition));
    }

    // TODO De-duplicate
    /**
     * Parses the given cluster definition and returns a map representing the
     * content.
     * 
     * @param clusterDefinition raw cluster definiton as provided by the request
     * @return cluster definition as map for further processing, <code>null</code>
     *         if no definition provided
     * @throws MalformedClusterDefinitionException if the cluster definition file is
     *                                             not valid
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> clusterDefinition(String clusterDefinition) {
        if (StringUtils.isBlank(clusterDefinition)) {
            return null;
        }

        Map<String, Object> clusterDefinitionMap = null;
        if (clusterDefinition != null) {
            try {
                clusterDefinitionMap = new ObjectMapper(new YAMLFactory()).readValue(clusterDefinition, Map.class);
            } catch (JsonProcessingException e) {
                throw new MalformedClusterDefinitionException(
                        "The cluster definition does not contain valid YAML/JSON.", e);
            }
        }

        if (!"t2.stackable.tech/v1".equals(clusterDefinitionMap.get("apiVersion"))) {
            throw new MalformedClusterDefinitionException("The apiVersion is either missing or not valid.");
        }

        if (!"Infra".equals(clusterDefinitionMap.get("kind"))) {
            throw new MalformedClusterDefinitionException(
                    "The kind of requested resource is either missing or not valid.");
        }

        return clusterDefinitionMap;
    }

}
