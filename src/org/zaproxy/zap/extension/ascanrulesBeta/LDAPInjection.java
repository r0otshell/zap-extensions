/**
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.zaproxy.zap.extension.ascanrulesBeta;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang.RandomStringUtils;
import org.apache.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.core.scanner.AbstractAppParamPlugin;
import org.parosproxy.paros.core.scanner.Alert;
import org.parosproxy.paros.core.scanner.Category;
import org.parosproxy.paros.core.scanner.NameValuePair;
import org.parosproxy.paros.network.HttpMessage;

/**
 * The LDAPInjection plugin identifies LDAP injection vulnerabilities with LDAP based login pages, and LDAP searches
 *
 * @author 70pointer
 */
public class LDAPInjection extends AbstractAppParamPlugin {

    /**
     * plugin dependencies
     */
    private static final String[] dependency = {};
    /**
     * for logging.
     */
    private static Logger log = Logger.getLogger(LDAPInjection.class);
    /**
     * determines if we should output Debug level logging
     */
    private boolean debugEnabled = log.isDebugEnabled();
    //TODO: append "&;" to this string, once they do not incorrectly cause the Sites tab to grow an extra limb!
    private static final String errorAttack = "|!<>=~=>=<=*(),+-\"'\\/";   
    //Note the ampersand at the end.. causes problems if earlier in the string..
    //and the semicolon after that..
    // ZAP: Added a static error bundle to speed up the implementation
    // LDAP errors for Injection testing
    // Use an inverse map to avoid multimap use
    // ----------------------------------------
    private static final Map<Pattern, String> LDAP_ERRORS = new HashMap<Pattern, String>();
    private int matchThreshold = 0;
    private int andRequests =0;
	
	//characters used in the generation of random parameters
	private static final char[] RANDOM_PARAMETER_CHARS = "abcdefghijklmnopqrstuvwyxz0123456789".toCharArray();
    
    static {
        String ldapImplementationsFlat = Constant.messages.getString("ascanbeta.ldapinjection.knownimplementations");
        String[] ldapImplementations = ldapImplementationsFlat.split(":");
        String errorMessageFlat;
        String[] errorMessages;
        Pattern errorPattern;

        for (String ldapImplementation : ldapImplementations) {  //for each LDAP implementation
            //for each known LDAP implementation
            errorMessageFlat = Constant.messages.getString("ascanbeta.ldapinjection." + ldapImplementation + ".errormessages");
            errorMessages = errorMessageFlat.split(":");

            for (String errorMessage : errorMessages) {  //for each error message for the given LDAP implemention
                //compile it into a pattern
                errorPattern = Pattern.compile(errorMessage);

                //add it to the errors list together with the ldap implementation
                LDAP_ERRORS.put(errorPattern, ldapImplementation);
            }
        }
    }
    
    // use Hirshberg to calculate longest common substring between two strings.
    private static final Hirshberg hirshberg = new Hirshberg ();

    @Override
    public int getId() {
        return 40015;
    }

    @Override
    public String getName() {
        return Constant.messages.getString("ascanbeta.ldapinjection.name");
    }

    @Override
    public String[] getDependency() {
        return dependency;
    }

    @Override
    public String getDescription() {
        return Constant.messages.getString("ascanbeta.ldapinjection.desc");
    }

    @Override
    public int getCategory() {
        return Category.INJECTION;
    }

    @Override
    public String getSolution() {
        return Constant.messages.getString("ascanbeta.ldapinjection.soln");
    }

    @Override
    public String getReference() {
        return Constant.messages.getString("ascanbeta.ldapinjection.refs");
    }

    @Override
    public void init() {
        //DEBUG: turn on for debugging
        //log.setLevel(org.apache.log4j.Level.DEBUG);
        //this.debugEnabled = true;

        if (this.debugEnabled) {
            log.debug("Initialising");
        }
        //set up the match threshold percentages based on the alert threshold.
        //allow for the use of common libraries (etc) in both pass/fail cases by skewing towards the upper end of the range.
        switch (this.getAlertThreshold() ) {
        	case HIGH:
        		this.matchThreshold = 95; 
        		break;
        	case MEDIUM:
        		this.matchThreshold = 65;  
        		break;
        	case LOW:
        		this.matchThreshold = 40;
        		break;
        	//this case cannot currently be selected in the GUI, so it doesn't make much sense. 
        	//But hey. For now, make it the same as "LOW"
        	case OFF:
        		this.matchThreshold = 40;
        		break;
        	default:
        		break;
        }
        //how hard should we try to find an LDAP injection point (primarily by looking at how deeply embedded it might be in paremtheses)
        //this is important in complex LDAP expressions which are deeply nested, i.e., where there are various AND, OR, or NOT expressions 
        //(&, |, ! respectively in LDAP)
        switch (this.getAttackStrength()) {
	        case INSANE:
	        	this.andRequests=16;
	        	break;
	        case HIGH:
	        	this.andRequests=8;
	        	break;
	        case MEDIUM:
	        	this.andRequests=4;
	        	break;
	        case DEFAULT:
	        	this.andRequests=4;
	        	break;
	        case LOW:
	        	this.andRequests=2;
	        	break;
	        }
    }

    public void scan(HttpMessage msg, NameValuePair originalParam) {
    	/*
    	 * Scan everything _except_ URL path parameters.
    	 * URL Path parameters are problematic for the matching based scanners, because changing the URL path
    	 * "parameter" generates output that is wildly different from the unmodified URL path "parameter" 
    	 */
    	if (originalParam.getType() != NameValuePair.TYPE_URL_PATH) {
    		super.scan(msg, originalParam);
    	}
    }

    /**
     * scans the user specified parameter for LDAP injection
     * vulnerabilities. Requires one extra request for each parameter checked
     */
	public void scan(HttpMessage originalmsg, String paramname, String paramvalue) {
		
		//for the purposes of our logic, we can handle a NULL parameter as an empty string. Saves on NPEs.
		if (paramvalue==null) paramvalue="";
		
        try {
	        	if (this.debugEnabled) {
	                log.debug("Scanning URL [" + originalmsg.getRequestHeader().getMethod() + "] [" + originalmsg.getRequestHeader().getURI() + "],  [" + paramname + "] with value [" + paramvalue + "] for LDAP Injection");
	            }
	        	
	        	//get the response for the "original" unmodified request
	        	//this fixes what seems to be a bug in the Zap core, where the request response is not actually available at this point via "originalmsg"
	        	sendAndReceive(originalmsg);
	        	
        		//1: try error based LDAP injection, for one of the LDAP implementations that we know about
                HttpMessage attackMsg = getNewMsg();                                                
                //set a new parameter.. with a value designed to cause an LDAP error to occur
                this.setParameter(attackMsg, paramname, errorAttack);
                //send it, and see what happens :)
                sendAndReceive(attackMsg);
                if ( checkResultsForLDAPAlert(attackMsg, /*currentHtmlParameter.getType().toString(), */ paramname))  {
                	return;
                }
                
                //bale out if we were asked nicely
                if (isStop()) { 
                	if (log.isDebugEnabled() ) log.debug("Stopping the scan due to a user request");
                	return;
                	}
                
                //otherwise continue to check for non error-based LDAP injection, using boolean based logic.                
                //first check stability of the output for the original parameter.
                //if its not stable (enough), there is not much point in continuing
                HttpMessage repeatMsg = getNewMsg();
                sendAndReceive(repeatMsg);
                int repeatMatch = this.calcMatchPercentage(originalmsg.getResponseBody().toString(), repeatMsg.getResponseBody().toString());
                log.debug("Got percentage for repeat: "+ repeatMatch);
                if (repeatMatch < matchThreshold ) {
                	//the URL is not stable, based on the threshold level set. bale.
                	log.debug ("The output is not stable for the original URL. Re-playing it resulted in a match of "+ repeatMatch + "%, compared to a threshold of "+ matchThreshold + "%");
                	return;
                }
                
                //bale out if we were asked nicely
                if (isStop()) { 
                	if (log.isDebugEnabled() ) log.debug("Stopping the scan due to a user request");
                	return;
                	}
                
                //now try a random parameter of the same length, to make sure that changing it results in output substantially DIFFERENT to the original
				//get a random parameter value the same length as the original!
                String randomparameterAttack = RandomStringUtils.random(paramvalue.length(), RANDOM_PARAMETER_CHARS);
				if ( this.debugEnabled ) log.debug("The random parameter chosen was ["+randomparameterAttack+"]");				

				HttpMessage randomParamMsg1 = getNewMsg();
                this.setParameter(randomParamMsg1, paramname, randomparameterAttack);
                sendAndReceive(randomParamMsg1);
                
                //bale out if we were asked nicely
                if (isStop()) { 
                	if (log.isDebugEnabled() ) log.debug("Stopping the scan due to a user request");
                	return;
                	}

				HttpMessage randomParamMsg2 = getNewMsg();
                this.setParameter(randomParamMsg2, paramname, randomparameterAttack);
                sendAndReceive(randomParamMsg2);
                
                int randomVersusRandomMatch = this.calcMatchPercentage(randomParamMsg1.getResponseBody().toString(), randomParamMsg2.getResponseBody().toString());
                log.debug("Got percentage match for a random parameter (against another identical request): "+ randomVersusRandomMatch);
                if (! (randomVersusRandomMatch > matchThreshold)) {
                	//the output for the random parameter is .
                	log.debug ("The output for a random parameter is unstable. It resulted in a match of "+ randomVersusRandomMatch + "%, compared to a threshold of "+ matchThreshold + "%");
                	return;
                }
                
                //now check the random against the original, to make sure the output is different
                int randomVersusOriginalMatch = this.calcMatchPercentage(randomParamMsg1.getResponseBody().toString(), originalmsg.getResponseBody().toString());
                log.debug("Got percentage match for a random parameter against the original parameter: "+ randomVersusOriginalMatch + "%, compared to a threshold of "+ matchThreshold + "%");
                if (randomVersusOriginalMatch > matchThreshold) {
                	//the output for the random parameter is .
                	log.debug ("The output for a random parameter is too similar to the output for the original parameter. It resulted in a match of "+ randomVersusOriginalMatch + "%, compared to a threshold of "+ matchThreshold + "%");
                	return;
                }

                //bale out if we were asked nicely
                if (isStop()) { 
                	if (log.isDebugEnabled() ) log.debug("Stopping the scan due to a user request");
                	return;
                	}
                
                //1: The following logic is designed for login pages, which is the primary use case of LDAP within web apps
	        	//try inserting a logically equivalent expression, to see if we get a match for the original output
                //because of the syntax we need to build up, start with 1, not 0. 
                for (int andAttackNumber = 1; andAttackNumber <= this.andRequests; andAttackNumber ++) {
                	//build up something like one of the following, depending on the iteration we're in:
                	//Note there the first case has 1 ')' and 1 '('
                	// )(objectClass=*
                	// ))((objectClass=*
                	// )))(((objectClass=*
                	StringBuffer temp = new StringBuffer().append(paramvalue);
                	for (int i=0; i< andAttackNumber; i++) temp.append(')');
                	for (int i=0; i< andAttackNumber; i++) temp.append('(');
                	temp.append("objectClass=*");
                	
                	String appendTrueAttack = new String (temp);
	        		
	        		HttpMessage appendTrueMsg = getNewMsg();
	                this.setParameter(appendTrueMsg, paramname, appendTrueAttack);
	                sendAndReceive(appendTrueMsg);
	                
	                int appendTrueVersusOriginalMatch = this.calcMatchPercentage(appendTrueMsg.getResponseBody().toString(), originalmsg.getResponseBody().toString());
	                log.debug("Got percentage for append TRUE expression ["+appendTrueAttack+"] versus original: "+ appendTrueVersusOriginalMatch);
	                if (appendTrueVersusOriginalMatch > this.matchThreshold) {
	                	
	                	log.debug(appendTrueAttack + " seems to produce sufficiently equivalent results to the original");	                	
	                	log.debug("We found an LDAP injection");
	                	
                    	String extraInfo = Constant.messages.getString("ascanbeta.ldapinjection.booleanbased.alert.extrainfo",
                                paramname,
                                getBaseMsg().getRequestHeader().getMethod(),
                                getBaseMsg().getRequestHeader().getURI().getURI(),
                                appendTrueAttack,
                                randomparameterAttack
                                );

                        String vulnevidence = "";  //there is no String to search for in the original output.  all extra info is in extra info field. ahem!
                        String attack = Constant.messages.getString("ascanbeta.ldapinjection.booleanbased.alert.attack", appendTrueAttack, randomparameterAttack);
                        String vulnname = Constant.messages.getString("ascanbeta.ldapinjection.name");
                        String vulndesc = Constant.messages.getString("ascanbeta.ldapinjection.desc");
                        String vulnsoln = Constant.messages.getString("ascanbeta.ldapinjection.soln");
                        
                        //bingo!
                        bingo(Alert.RISK_HIGH, Alert.CONFIRMED, vulnname, vulndesc,
                                getBaseMsg().getRequestHeader().getURI().getURI(),
                                paramname, 
                                attack,
                                extraInfo, 
                                vulnsoln, 
                                vulnevidence,
                                getBaseMsg());

                        //and log it
                        String logMessage = Constant.messages.getString("ascanbeta.ldapinjection.booleanbased.alert.logmessage",
                                getBaseMsg().getRequestHeader().getMethod(),
                                getBaseMsg().getRequestHeader().getURI().getURI(),
                                paramname,
                                appendTrueAttack, randomparameterAttack);
                        log.info(logMessage);
                        
	                	//all done for this parameter. return.
	                	return;
	                	}
	                //bale out if we were asked nicely
	                if (isStop()) { 
	                	if (log.isDebugEnabled() ) log.debug("Stopping the scan due to a user request");
	                	return;
	                	}
	        	}
                
                //2: try a separate case for where the LDAP injection point is *not* wrapped in parentheses, like the following complete filter expression:
                // "sn=Joe Bloggs"
                //this is very likely to be found in LDAP-based search pages and the like.
                //so what we do here is to use wildcards inserted into the middle of the original value, to create a (hopefully) logically equivalent filter expression
                //like the following:
                // "sn=Joe *loggs"
                //but only do this if the param length is > 1, to eliminate false positives to some degree.
                int paramLength = paramvalue.length();
                if ( paramLength > 1 ) {
                	
                	StringBuffer temp = new StringBuffer().append(paramvalue.substring (0, (paramLength / 2) -1));  //if len == 10 => gets chars at 0-3 (ie, 4 chars)
                	temp.append('*');
                	temp.append(paramvalue.substring (paramLength / 2));  //if len == 10 => gets chars at 5- (ie, 5 chars)                	
                	String hopefullyTrueAttack = new String (temp);
                	
                	log.debug("Trying for LDAP injection with the following '*' based attack: ["+ temp +"], compared to the original value ["+ paramvalue+ "]");
	        		
	        		HttpMessage hopefullyTrueMsg = getNewMsg();
	                this.setParameter(hopefullyTrueMsg, paramname, hopefullyTrueAttack);
	                sendAndReceive(hopefullyTrueMsg);
	                
	                int hopefullyTrueVersusOriginalMatch = this.calcMatchPercentage(hopefullyTrueMsg.getResponseBody().toString(), originalmsg.getResponseBody().toString());
	                log.debug("Got percentage for hopefully TRUE expression ["+hopefullyTrueAttack+"] versus original: "+ hopefullyTrueVersusOriginalMatch);
	                if (hopefullyTrueVersusOriginalMatch > this.matchThreshold) {
	                	
	                	log.debug(hopefullyTrueAttack + " seems to produce sufficiently equivalent results to the original");	                	
	                	log.debug("We found an LDAP injection");
	                	
                    	String extraInfo = Constant.messages.getString("ascanbeta.ldapinjection.booleanbased.alert.extrainfo",
                                paramname,
                                getBaseMsg().getRequestHeader().getMethod(),
                                getBaseMsg().getRequestHeader().getURI().getURI(),
                                hopefullyTrueAttack,
                                randomparameterAttack
                                );

                        String vulnevidence = "";  //there is no String to search for in the original output.  all extra info is in extra info field. ahem!
                        String attack = Constant.messages.getString("ascanbeta.ldapinjection.booleanbased.alert.attack", hopefullyTrueAttack, randomparameterAttack);
                        String vulnname = Constant.messages.getString("ascanbeta.ldapinjection.name");
                        String vulndesc = Constant.messages.getString("ascanbeta.ldapinjection.desc");
                        String vulnsoln = Constant.messages.getString("ascanbeta.ldapinjection.soln");
                        
                        //bingo!
                        bingo(Alert.RISK_HIGH, Alert.CONFIRMED, vulnname, vulndesc,
                                getBaseMsg().getRequestHeader().getURI().getURI(),
                                paramname, 
                                attack,
                                extraInfo, 
                                vulnsoln, 
                                vulnevidence,
                                getBaseMsg());

                        //and log it
                        String logMessage = Constant.messages.getString("ascanbeta.ldapinjection.booleanbased.alert.logmessage",
                                getBaseMsg().getRequestHeader().getMethod(),
                                getBaseMsg().getRequestHeader().getURI().getURI(),
                                paramname,
                                hopefullyTrueAttack, randomparameterAttack);
                        log.info(logMessage);
                        
	                	//all done for this parameter. return.
	                	return;
	                }	                
	        	} else {
	        		log.debug("The parameter value ["+ paramvalue + " is too short to try inserting wildcards, to find logically equivalent expressions");
	        	}
                
                //TODO: add additional logic here to handle LDAP based searches (as opposed to LDAP based login pages), 
                //by using the "*" LDAP expression, to eek out more data from the LDAP directory into the response.
                //but that's a task for another day.

        } catch (Exception e) {
            //Do not try to internationalise this.. we need an error message in any event.. 
            //if it's in English, it's still better than not having it at all. 
            log.error("An error occurred checking a url for LDAP Injection issues", e);
        }
    }
	
	/**
	 * calculate the percentage length of similarity between 2 strings.
	 * @param a 
	 * @param b
	 * @return
	 */
	private int calcMatchPercentage (String a, String b) {
		//log.debug("About to get LCS for [" + a +"] and [ "+ b + "]");
		if ( a == null && b == null )
			return 100;
		if ( a == null || b == null )
			return 0;
		if ( a.length() == 0 && b.length() == 0)
			return 100;
		if ( a.length() == 0 || b.length() == 0)
			return 0;
		String lcs = hirshberg.lcs(a, b);
		//log.debug("Got LCS: "+ lcs); 
		//get the percentage match against the longer of the 2 strings
		return (int) ( ( ((double)lcs.length()) / Math.max (a.length(), b.length())) * 100) ;
		
	}

    /**
     * returns does the Message Response match the pattern provided?
     *
     * @param msg the Message whose response we will examine
     * @param pattern the pattern which we will look for in the Message Body
     * @return true/false. D'uh! (It being a boolean, and all that)
     */
    protected boolean responseMatches(HttpMessage msg, Pattern pattern) {
        Matcher matcher = pattern.matcher(msg.getResponseBody().toString());
        return matcher.find();
    }

    /**
     *
     * @param message
     * @param parameterType
     * @param parameterName
     * @return
     * @throws Exception
     */
    private boolean checkResultsForLDAPAlert(HttpMessage message, /*String parameterType, */ String parameterName) throws Exception {
        //compare the request response with each of the known error messages, 
        //for each of the known LDAP implementations.
        //in order to minimise false positives, only consider a match 
        //for the error message in the response if the string also 
        //did NOT occur in the original (unmodified) response
        for (Pattern errorPattern : LDAP_ERRORS.keySet()) {

            //if the pattern was found in the new response, 
            //but not in the original response (for the unmodified request)
            //then we have a match.. LDAP injection!
            if (	responseMatches(message, errorPattern)
                    && !responseMatches(getBaseMsg(), errorPattern)) {

                //the HTML matches one of the known LDAP errors.
                //so raise the error, and move on to the next parameter
            	
                String extraInfo = Constant.messages.getString("ascanbeta.ldapinjection.alert.extrainfo",
                        /*parameterType,*/
                        parameterName,
                        getBaseMsg().getRequestHeader().getMethod(),
                        getBaseMsg().getRequestHeader().getURI().getURI(),
                        errorAttack, 
                        LDAP_ERRORS.get(errorPattern), 
                        errorPattern);

                String attack = Constant.messages.getString("ascanbeta.ldapinjection.alert.attack", /*parameterType, */ parameterName, errorAttack);
                String vulnname = Constant.messages.getString("ascanbeta.ldapinjection.name");
                String vulndesc = Constant.messages.getString("ascanbeta.ldapinjection.desc");
                String vulnsoln = Constant.messages.getString("ascanbeta.ldapinjection.soln");
                
                //we know the LDAP implementation, so put it in the title, where it will be obvious.
                bingo(Alert.RISK_HIGH, Alert.CONFIRMED, vulnname + " - " + LDAP_ERRORS.get(errorPattern), vulndesc,
                        getBaseMsg().getRequestHeader().getURI().getURI(),
                        parameterName, 
                        attack,
                        extraInfo, 
                        vulnsoln, 
                        errorPattern.toString(),
                        message); //use the attack message, rather than the original message.

                //and log it                
                String logMessage = Constant.messages.getString("ascanbeta.ldapinjection.alert.logmessage",
                        getBaseMsg().getRequestHeader().getMethod(),
                        getBaseMsg().getRequestHeader().getURI().getURI(),
                        /* parameterType, */
                        parameterName,
                        errorAttack, 
                        LDAP_ERRORS.get(errorPattern), 
                        errorPattern);

                log.info(logMessage);

                return true;  //threw an alert
            }

        } //for each error message for the given LDAP implemention

        return false;  //did not throw an alert
    }

    /**
     *
     * @return
     */
    @Override
    public int getRisk() {
        return Alert.RISK_HIGH;
    }

    /**
     *
     * @return
     */
    @Override
    public int getCweId() {
        return 90;
    }

    /**
     *
     * @return
     */
    @Override
    public int getWascId() {
        return 29;
    }

}