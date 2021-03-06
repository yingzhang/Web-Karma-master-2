package edu.isi.karma.web.services.rdf;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;

import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.apache.commons.collections.map.LRUMap;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.auth.DigestScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;

import edu.isi.karma.config.ModelingConfiguration;
import edu.isi.karma.controller.update.UpdateContainer;
import edu.isi.karma.er.helper.TripleStoreUtil;
import edu.isi.karma.kr2rml.ContextGenerator;
import edu.isi.karma.kr2rml.ContextIdentifier;
import edu.isi.karma.kr2rml.URIFormatter;
import edu.isi.karma.kr2rml.mapping.R2RMLMappingIdentifier;
import edu.isi.karma.kr2rml.writer.JSONKR2RMLRDFWriter;
import edu.isi.karma.kr2rml.writer.KR2RMLRDFWriter;
import edu.isi.karma.kr2rml.writer.N3KR2RMLRDFWriter;
import edu.isi.karma.metadata.KarmaMetadataManager;
import edu.isi.karma.metadata.PythonTransformationMetadata;
import edu.isi.karma.metadata.UserConfigMetadata;
import edu.isi.karma.metadata.UserPreferencesMetadata;
import edu.isi.karma.modeling.semantictypes.SemanticTypeUtil;
import edu.isi.karma.rdf.GenericRDFGenerator;
import edu.isi.karma.rdf.GenericRDFGenerator.InputType;
import edu.isi.karma.rdf.RDFGeneratorRequest;
import edu.isi.karma.util.HTTPUtil.HTTP_HEADERS;
import edu.isi.karma.webserver.KarmaException;

@Path("/r2rml")
public class RDFGeneratorServlet {

	private static final int MODEL_CACHE_SIZE = 20;
	private static Logger logger = LoggerFactory
			.getLogger(RDFGeneratorServlet.class);
	private static LRUMap modelCache = new LRUMap(MODEL_CACHE_SIZE);

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/rdf")
	public String RDF(MultivaluedMap<String, String> formParams) {
		try {
			logger.info("Path - r2rml/rdf . Generate and return RDF as String");
			return getRDF(formParams);
		} catch (Exception e) {
			logger.error("Error generating RDF", e);
			return "Exception: " + e.getMessage();
		}

	}
	
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/json")
	public String JSON(MultivaluedMap<String, String> formParams) {
		try {
			logger.info("Path - r2rml/json . Generate and return JSON ld as String");
			return getJSON(formParams);
		} catch (Exception e) {
			logger.error("Error generating JSON", e);
			return "Exception: " + e.getMessage();
		}

	}

	/**
	 * 
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @throws JSONException
	 * @throws KarmaException
	 */

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/clearCache")
	public Response clearCache(MultivaluedMap<String, String> formParams) {
		modelCache.clear();
		return Response.status(200).entity("Success").build();
	}

	/**
	 * 
	 * @throws ClientProtocolException
	 * @throws IOException
	 * @throws JSONException
	 * @throws KarmaException
	 */

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/sparql")
	public Response saveToTriplestore(MultivaluedMap<String, String> formParams) {
		try {
			logger.info("Path - r2rml/sparql. Store RDF to triplestore and return the Response");

			logger.info("Generating RDF for: "
					+ formParams.getFirst(FormParameters.RAW_DATA));

			String strRDF = getRDF(formParams);
			if (strRDF != null) {
				int responseCode = PublishRDFToTripleStore(formParams, strRDF);

				// TODO Make it better
				if (responseCode == 200 || responseCode == 201)
					return Response.status(responseCode).entity("Success")
							.build();
				else
					return Response.status(responseCode)
							.entity("Failure: Check logs for more information")
							.build();
			}

			return Response.status(401)
					.entity("Failure: Check logs for more information").build();

		} catch (Exception e) {
			logger.error("Exception : " + e.getMessage());
			return Response.status(401)
					.entity("Exception : " + e.getMessage()).build();
		}

	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Path("/rdf/sparql")
	public String saveAndReturnRDF(MultivaluedMap<String, String> formParams) {
		try {
			logger.info("Path - r2rml/rdf/sparql. Store RDF to triplestore and return the Response");

			String strRDF = getRDF(formParams);

			if (strRDF != null) {
				int responseCode = PublishRDFToTripleStore(formParams, strRDF);

				// TODO Make it better
				if (responseCode == 200 || responseCode == 201)
					logger.info("Successfully completed");
				else
					logger.error("There was an error while publishing to Triplestore");

			}

			return strRDF;
		} catch (Exception e) {
			logger.error("Exception : " + e.getMessage());
			return "Exception : " + e.getMessage();
		}
	}

	/*
	 * If URL is provided, data will be fetched from the URL, else raw Data in
	 * JSON, CSV or XML should be provided
	 */

	private String getRDF(MultivaluedMap<String, String> formParams)
			throws JSONException, MalformedURLException, KarmaException,
			IOException {

		boolean refreshModel = false;
		if (formParams.containsKey(FormParameters.REFRESH_MODEL)
				&& formParams.getFirst(FormParameters.REFRESH_MODEL)
						.equalsIgnoreCase("true"))
			refreshModel = true;

		if (formParams.containsKey(FormParameters.DATA_URL)
				&& formParams.getFirst(FormParameters.DATA_URL).trim() != "")
			return GenerateRDF(
					new URL(formParams.getFirst(FormParameters.DATA_URL))
							.openStream(),
					formParams.getFirst(FormParameters.R2RML_URL), formParams
							.getFirst(FormParameters.CONTENT_TYPE),
					refreshModel);

		if (formParams.containsKey(FormParameters.RAW_DATA)
				&& formParams.getFirst(FormParameters.RAW_DATA).trim() != "")
			return GenerateRDF(formParams.getFirst(FormParameters.RAW_DATA),
					formParams.getFirst(FormParameters.R2RML_URL),
					formParams.getFirst(FormParameters.CONTENT_TYPE),
					refreshModel);

		return null;
	}
	
	private String getJSON(MultivaluedMap<String, String> formParams) throws JSONException, MalformedURLException, KarmaException, IOException{
		
		boolean refreshModel = false;
		if (formParams.containsKey(FormParameters.REFRESH_MODEL)
				&& formParams.getFirst(FormParameters.REFRESH_MODEL)
						.equalsIgnoreCase("true"))
			refreshModel = true;
		
		if (formParams.containsKey(FormParameters.DATA_URL)	&& formParams.getFirst(FormParameters.DATA_URL).trim() != "") {
			return GenerateJSON(new URL(formParams.getFirst(FormParameters.DATA_URL)).openStream(),
								formParams.getFirst(FormParameters.R2RML_URL), 
								formParams.getFirst(FormParameters.CONTENT_TYPE),
								refreshModel,
								GenerateContext(formParams.getFirst(FormParameters.R2RML_URL)));
		}

		if (formParams.containsKey(FormParameters.RAW_DATA)	&& formParams.getFirst(FormParameters.RAW_DATA).trim() != ""){
			return GenerateJSON(formParams.getFirst(FormParameters.RAW_DATA),
								formParams.getFirst(FormParameters.R2RML_URL),
								formParams.getFirst(FormParameters.CONTENT_TYPE),
								refreshModel,
								GenerateContext(formParams.getFirst(FormParameters.R2RML_URL)));
		}
		
		return null;
		
	}

	private int PublishRDFToTripleStore(
			MultivaluedMap<String, String> formParams, String strRDF)
			throws ClientProtocolException, IOException, KarmaException {
		String tripleStoreURL = getTripleStoreURL(formParams);

		Boolean overWrite = Boolean.parseBoolean(formParams
				.getFirst(FormParameters.OVERWRITE));
		int responseCode;

		logger.info("Publishing RDF to TripleStore: " + tripleStoreURL);

		switch (formParams.getFirst(FormParameters.TRIPLE_STORE)) {
		case FormParameters.TRIPLE_STORE_VIRTUOSO:
			URL url = new URL(tripleStoreURL);

			HttpHost httpHost = new HttpHost(url.getHost(), url.getPort(),
					url.getProtocol());

			HttpPost httpPost = new HttpPost(tripleStoreURL);

			httpPost.setEntity(new StringEntity(strRDF));

			if (overWrite) // Manually delete everything if overWrite is set to
							// true for Virtuoso
				invokeHTTPDeleteWithAuth(httpHost, tripleStoreURL,
						formParams.getFirst(FormParameters.USERNAME),
						formParams.getFirst(FormParameters.PASSWORD));

			responseCode = invokeHTTPRequestWithAuth(httpHost, httpPost,
					MediaType.APPLICATION_XML, null,
					formParams.getFirst(FormParameters.USERNAME),
					formParams.getFirst(FormParameters.PASSWORD));

			break;

		case FormParameters.TRIPLE_STORE_SESAME:
			TripleStoreUtil tsu = new TripleStoreUtil();

			// TODO Find purpose of Graph URI and replace it with formParams
			String baseURI = "http://isiimagefinder/";

			boolean success = tsu.saveToStoreFromString(strRDF, tripleStoreURL,
					formParams.getFirst(FormParameters.GRAPH_URI), overWrite,
					baseURI);
			responseCode = (success) ? 200 : 503; // HTTP OK or Error
			break;
		default:
			responseCode = 404;
			break;
		}

		return responseCode;
	}

	private String getTripleStoreURL(MultivaluedMap<String, String> formParams) {
		StringBuilder sbTSURL = new StringBuilder();

		if (formParams.getFirst(FormParameters.SPARQL_ENDPOINT) != null
				|| formParams.getFirst(FormParameters.SPARQL_ENDPOINT).trim() != "")
			sbTSURL.append(formParams.getFirst(FormParameters.SPARQL_ENDPOINT));

		if (formParams.getFirst(FormParameters.TRIPLE_STORE).equals(
				FormParameters.TRIPLE_STORE_VIRTUOSO))
			if (formParams.getFirst(FormParameters.GRAPH_URI) != null
					|| formParams.getFirst(FormParameters.GRAPH_URI).trim() != "") {
				sbTSURL.append("?graph-uri=");
				sbTSURL.append(formParams.getFirst(FormParameters.GRAPH_URI));
			}

		return sbTSURL.toString();

	}

	private String GenerateRDF(InputStream dataStream, String r2rmlURI,
			String dataType, boolean refreshR2RML) throws KarmaException,
			JSONException, IOException { // logger.info(dataStream.toString());
		logger.info(r2rmlURI);
		logger.info(dataType);

		GenericRDFGenerator gRDFGen = new GenericRDFGenerator(null);

		R2RMLMappingIdentifier rmlID = new R2RMLMappingIdentifier(r2rmlURI,
				new URL(r2rmlURI));
		gRDFGen.addModel(rmlID);

		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);

		URIFormatter uriFormatter = new URIFormatter();
		KR2RMLRDFWriter outWriter = new N3KR2RMLRDFWriter(uriFormatter, pw);

		String sourceName = r2rmlURI;
		RDFGeneratorRequest request = new RDFGeneratorRequest(rmlID.getName(), sourceName);
		request.addWriter(outWriter);
		request.setInputStream(dataStream);
		request.setDataType(InputType.valueOf(dataType));
		request.setAddProvenance(false);
		request.setMaxNumLines(-1);
		gRDFGen.generateRDF(request);

		return sw.toString();

	}

	private String GenerateRDF(String rawData, String r2rmlURI,
			String dataType, boolean refreshModel) throws KarmaException,
			JSONException, IOException {
		logger.info(rawData);

		return GenerateRDF(IOUtils.toInputStream(rawData), r2rmlURI, dataType,
				refreshModel);
	}
	
	private String GenerateJSON(String rawData, String r2rmlURI,
			String dataType, boolean refreshModel,String jsonContext) throws KarmaException,
			JSONException, IOException {
		//logger.info(rawData);

		return GenerateJSON(IOUtils.toInputStream(rawData), r2rmlURI, dataType,
				refreshModel,jsonContext);
	}
	
	private String GenerateJSON(InputStream dataStream, String r2rmlURI,
			String dataType, boolean refreshR2RML,String jsonContext) throws KarmaException,
			JSONException, IOException { // logger.info(dataStream.toString());
		//logger.info(r2rmlURI);
		//logger.info(dataType);
		
		String r2rmlFileName = new File(r2rmlURI).getName();
        String contextFileName = r2rmlFileName.substring(0,r2rmlFileName.length()-4) + "_context.json";
        
        File contextFile = new File("context/"+contextFileName);
        
        if(!contextFile.exists()){
        	contextFile.createNewFile();
        }
        
        FileWriter fw = new FileWriter(contextFile);
        
        BufferedWriter bw = new BufferedWriter(fw);
        
        bw.write(jsonContext);
        
        bw.close();
		
		GenericRDFGenerator rdfGen = new GenericRDFGenerator(null);

		// Add the models in;
		R2RMLMappingIdentifier modelIdentifier = new R2RMLMappingIdentifier(
				"generic-model", new URL(r2rmlURI)); 
		rdfGen.addModel(modelIdentifier);

		//String filename = "context/events.json";
		//String contextName = "context/events_context.json";
		logger.info("Loading json file: " + jsonContext);
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		//File contextFile =  new File(getTestResource(contextName).toURI()); GIVE FUCKS LATER
		
		
		JSONTokener token = new JSONTokener(IOUtils.toInputStream(jsonContext)); //PERFECCT
		//logger.info(new JSONObject(token).getString("@context"));
		ContextIdentifier contextId = new ContextIdentifier("generic-context", contextFile.toURI().toURL());
		JSONKR2RMLRDFWriter writer = new JSONKR2RMLRDFWriter(pw);
		writer.setGlobalContext(new JSONObject(token), contextId); 
		RDFGeneratorRequest request = new RDFGeneratorRequest("generic-model", "whatsinthename");
		request.setInputStream(dataStream); //DOUBLE PERFECCT;input json take care of
		request.setAddProvenance(false);
		request.setDataType(InputType.JSON);
		request.addWriter(writer);
		rdfGen.generateRDF(request);
		String rdf = sw.toString();
		return rdf;

	}
	
	public String GenerateContext(String  r2rmlURI) throws MalformedURLException, IOException{
		
		Model model = ModelFactory.createDefaultModel();
        InputStream s = new URL(r2rmlURI).openStream(); //get the r2rml from URI
        model.read(s, null, "TURTLE");
        JSONObject top = new ContextGenerator(model, true).generateContext();
        return top.toString();
		
	}
	
	

	static {
		try {
			initialization();
		} catch (KarmaException ke) {
			logger.error("KarmaException: " + ke.getMessage());
		}
	}


	private static void initialization() throws KarmaException {
		UpdateContainer uc = new UpdateContainer();
		KarmaMetadataManager userMetadataManager = new KarmaMetadataManager();
		userMetadataManager.register(new UserPreferencesMetadata(), uc);
		userMetadataManager.register(new UserConfigMetadata(), uc);
		userMetadataManager.register(new PythonTransformationMetadata(), uc);

		SemanticTypeUtil.setSemanticTypeTrainingStatus(false);

		ModelingConfiguration.setLearnerEnabled(false); // disable automatic													// learning
	}

	private int invokeHTTPRequestWithAuth(HttpHost httpHost, HttpPost httpPost,
			String contentType, String acceptContentType, String userName,
			String password) throws ClientProtocolException, IOException {

		DefaultHttpClient httpClient = new DefaultHttpClient();

		if (acceptContentType != null && !acceptContentType.isEmpty()) {
			httpPost.setHeader(HTTP_HEADERS.Accept.name(), acceptContentType);
		}
		if (contentType != null && !contentType.isEmpty()) {
			httpPost.setHeader("Content-Type", contentType);
		}

		httpClient.getCredentialsProvider().setCredentials(
				new AuthScope(httpHost.getHostName(), httpHost.getPort()),
				new UsernamePasswordCredentials(userName, password));

		AuthCache authCache = new BasicAuthCache();
		DigestScheme digestScheme = new DigestScheme();

		digestScheme.overrideParamter("realm", "SPARQL"); // Virtuoso specific
		// digestScheme.overrideParamter("nonce", new Nonc);

		authCache.put(httpHost, digestScheme);

		BasicHttpContext localcontext = new BasicHttpContext();
		localcontext.setAttribute(ClientContext.AUTH_CACHE, authCache);

		// Execute the request
		HttpResponse response = httpClient.execute(httpHost, httpPost,
				localcontext);

		return response.getStatusLine().getStatusCode();
	}

	private int invokeHTTPDeleteWithAuth(HttpHost httpHost, String url,
			String userName, String password) throws ClientProtocolException,
			IOException {
		HttpDelete httpDelete = new HttpDelete(url);

		DefaultHttpClient httpClient = new DefaultHttpClient();

		httpClient.getCredentialsProvider().setCredentials(
				new AuthScope(httpHost.getHostName(), httpHost.getPort()),
				new UsernamePasswordCredentials(userName, password));

		AuthCache authCache = new BasicAuthCache();
		DigestScheme digestScheme = new DigestScheme();

		digestScheme.overrideParamter("realm", "SPARQL"); // Virtuoso specific
		// digestScheme.overrideParamter("nonce", new Nonc);

		authCache.put(httpHost, digestScheme);

		BasicHttpContext localcontext = new BasicHttpContext();
		localcontext.setAttribute(ClientContext.AUTH_CACHE, authCache);

		// Execute the request
		HttpResponse response = httpClient.execute(httpHost, httpDelete,
				localcontext);

		logger.info(Integer.toString(response.getStatusLine().getStatusCode()));

		return response.getStatusLine().getStatusCode();
	}

}
