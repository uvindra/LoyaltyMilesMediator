package org.wso2.sample.mediator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.SynapseConstants;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.mediators.AbstractMediator;
import org.apache.synapse.mediators.builtin.SendMediator;
import org.apache.synapse.transport.nhttp.NhttpConstants;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public class LoyaltyMilesMediator extends AbstractMediator {

    private static final String TRANSPORT_HEADERS = "TRANSPORT_HEADERS";
    private static final String JWT_HEADER = "X-JWT-Assertion";
    private static final String MILES_TIER_CLAIM = "http://wso2.org/claims/milestier";

    // Data format of expression,
    //      tier1:milesMultiplier1,tier2:milesMultiplier2,tier3:milesMultiplier3
    private String loyaltyMilesExpressions;

    private String milesTraveledProperty;
    private String calculatedLoyaltyMilesProperty;

    private Map<String, Double> tierMultiplierLookup = new HashMap<>();

    private static final Log log = LogFactory.getLog(LoyaltyMilesMediator.class);

    @Override
    public boolean mediate(MessageContext messageContext) {
        if (!loadLoyaltyMultiplierDefinition(messageContext)) {
            return false;
        }

        Map<String,Object> transportHeaders = (Map<String, Object>) ((Axis2MessageContext)messageContext)
                .getAxis2MessageContext().getProperty(TRANSPORT_HEADERS);

        String jwt = (String) transportHeaders.get(JWT_HEADER);
        String[] jwtParts = jwt.split("\\.");

        if (jwtParts.length == 3) {
            String base64EncodeClaims = jwtParts[1];

            String claimsJSONString = null;

            try {
                claimsJSONString = new String((Base64.getDecoder().decode(base64EncodeClaims)), "UTF-8");
            } catch (UnsupportedEncodingException e) {
                log.error("Error while decoding JWT", e);
                return false;
            }

            JsonObject claims = new JsonParser().parse(claimsJSONString).getAsJsonObject();

            String tier = claims.get(MILES_TIER_CLAIM).getAsString();

            if (tier == null) {
                log.error("Claim " + MILES_TIER_CLAIM + " does not exist");
                return triggerServerError(messageContext); // Interrupt execution flow
            }

            if (tierMultiplierLookup.containsKey(tier)) {
                Double multiplier = tierMultiplierLookup.get(tier);

                Object milesValue = messageContext.getProperty(milesTraveledProperty);

                if (milesValue != null) {
                    Integer milesTraveled = Integer.valueOf(milesValue.toString());

                    int loyaltyMiles = (int) (milesTraveled * multiplier);

                    messageContext.setProperty(calculatedLoyaltyMilesProperty, loyaltyMiles);
                } else {
                    log.error("Property " + milesTraveledProperty + " is not set");
                    return triggerServerError(messageContext); // Interrupt execution flow
                }
            } else {
                log.error("Miles multiplier for tier " + tier + " has not been defined");
                return triggerServerError(messageContext); // Interrupt execution flow
            }
        } else {
            log.error("JWT header format('{token infor}.{claims list}.{signature}') has been violated");
            return triggerServerError(messageContext); // Interrupt execution flow
        }


        return true;
    }

    public void setLoyaltyMilesExpressions(String loyaltyMilesExpressions) {
        this.loyaltyMilesExpressions = loyaltyMilesExpressions;
    }


    private boolean loadLoyaltyMultiplierDefinition(MessageContext messageContext) {
        if (loyaltyMilesExpressions == null) {
            log.error("Required Property 'loyaltyMilesExpressions' has not be defined in mediator");
            return triggerServerError(messageContext); // Interrupt execution flow
        }

        if (milesTraveledProperty == null) {
            log.error("Required Property 'milesTraveledProperty' has not be defined in mediator");
            return triggerServerError(messageContext); // Interrupt execution flow
        }

        if (calculatedLoyaltyMilesProperty == null) {
            log.error("Required Property 'calculatedLoyaltyMilesProperty' has not be defined in mediator");
            return triggerServerError(messageContext); // Interrupt execution flow
        }

        String[] expressions = loyaltyMilesExpressions.split(",");

        for (String expression : expressions) {
            final String[] tierMultiplier = expression.split(":");

            if (tierMultiplier.length == 2) {
                String tier = tierMultiplier[0];
                String multiplier = tierMultiplier[1];

                tierMultiplierLookup.put(tier, Double.valueOf(multiplier));
            } else {
                log.error("Expected 'tier:milesMultiplier' format violated in 'loyaltyMilesExpressions' property");
                return triggerServerError(messageContext); // Interrupt execution flow
            }
        }

        return true;
    }


    /**
     * Trigger an internal server error to denote that an unrecoverable issue has been encountered by the mediator.
     * This can be used to interrupt the execution flow.
     *
     * @param messageContext Synapse message context
     * @return always returns false
     */
    private boolean triggerServerError(MessageContext messageContext) {
        messageContext.setProperty(SynapseConstants.RESPONSE, "true");
        org.apache.axis2.context.MessageContext axis2MessageContext = ((Axis2MessageContext) messageContext).
                getAxis2MessageContext();
        axis2MessageContext.removeProperty(NhttpConstants.NO_ENTITY_BODY);
        axis2MessageContext.setProperty(NhttpConstants.HTTP_SC, "500");

        messageContext.setTo(null);
        SendMediator sendMediator = new SendMediator();
        sendMediator.mediate(messageContext);

        return false;
    }

    public void setCalculatedLoyaltyMilesProperty(String calculatedLoyaltyMilesProperty) {
        this.calculatedLoyaltyMilesProperty = calculatedLoyaltyMilesProperty;
    }

    public void setMilesTraveledProperty(String milesTraveledProperty) {
        this.milesTraveledProperty = milesTraveledProperty;
    }
}
