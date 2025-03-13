
package us.kbase.kbasejobservice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Generated;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import us.kbase.common.service.UObject;


/**
 * <p>Original spec-file type: RunJobParams</p>
 * <pre>
 * method - service defined in standard JSON RPC way, typically it's
 *     module name from spec-file followed by '.' and name of funcdef 
 *     from spec-file corresponding to running method (e.g.
 *     'KBaseTrees.construct_species_tree' from trees service);
 * params - the parameters of the method that performed this call;
 * service_ver - specific version of deployed service, last version is used 
 *     if this parameter is not defined (optional field);
 * rpc_context - context of current method call including nested call history
 *     (optional field, could be omitted in case there is no call history).
 * </pre>
 * 
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Generated("com.googlecode.jsonschema2pojo")
@JsonPropertyOrder({
    "method",
    "params",
    "service_ver",
    "rpc_context"
})
public class RunJobParams {

    @JsonProperty("method")
    private String method;
    @JsonProperty("params")
    private List<UObject> params;
    @JsonProperty("service_ver")
    private String serviceVer;
    private Map<String, Object> additionalProperties = new HashMap<String, Object>();

    @JsonProperty("method")
    public String getMethod() {
        return method;
    }

    @JsonProperty("method")
    public void setMethod(String method) {
        this.method = method;
    }

    public RunJobParams withMethod(String method) {
        this.method = method;
        return this;
    }

    @JsonProperty("params")
    public List<UObject> getParams() {
        return params;
    }

    @JsonProperty("params")
    public void setParams(List<UObject> params) {
        this.params = params;
    }

    public RunJobParams withParams(List<UObject> params) {
        this.params = params;
        return this;
    }

    @JsonProperty("service_ver")
    public String getServiceVer() {
        return serviceVer;
    }

    @JsonProperty("service_ver")
    public void setServiceVer(String serviceVer) {
        this.serviceVer = serviceVer;
    }

    public RunJobParams withServiceVer(String serviceVer) {
        this.serviceVer = serviceVer;
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperties(String name, Object value) {
        this.additionalProperties.put(name, value);
    }

    @Override
    public String toString() {
        return ((((((((("RunJobParams"+" [method=")+ method)+", params=")+ params)+", serviceVer=")+ serviceVer)+", additionalProperties=")+ additionalProperties)+"]");
    }

}
