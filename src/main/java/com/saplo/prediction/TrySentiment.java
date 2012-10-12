package com.saplo.prediction;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import au.com.bytecode.opencsv.CSVReader;
import au.com.bytecode.opencsv.CSVWriter;

import com.saplo.api.client.SaploClient;
import com.saplo.api.client.SaploClientException;
import com.saplo.api.client.entity.JSONRPCRequestObject;
import com.saplo.api.client.entity.JSONRPCResponseObject;
import com.saplo.api.client.entity.SaploCollection;
import com.saplo.api.client.entity.SaploCollection.Language;
import com.saplo.api.client.entity.SaploText;

public class TrySentiment {

	public static String APIKEY;
	public static String SECRETKEY;
	public static String INPUTFILE = "input.csv";
	public static String OUTPUTFILE = "output.csv";

	private final String DEFAULT_COLLECTION_NAME = "Trying Saplo Sentiment";
	private SaploCollection collection;
	private SaploClient client;
	private int modelId = 3827;

	private Map<Integer, PredictionText> texts = new HashMap<Integer, PredictionText>();
	private Set<String> uniqueTargetWords = new HashSet<String>();

	public TrySentiment() throws SaploClientException {

		/*
		 * Create a Saplo Client to communicate with Saplo API. If you don't
		 * have access to Saplo API already, register and get your API Key at
		 * http://saplo.com/api/signup
		 * 
		 * Download client from https://github.com/saplo/saplo4j or get it from
		 * Maven. For other languages (php, python, matlab) see
		 * https://github.com/saplo/
		 */
		client = new SaploClient.Builder(APIKEY, SECRETKEY).build();

	}

	private void run() throws SaploClientException, IOException, JSONException, InterruptedException {
		
		System.out.println("All input arguments looks good, lets run!");
		
		// 1. Check if collection exists else create collection
		checkCollectionElseCreate();

		// 2. Read input file and add texts to Saplo API
		readFileAndCreateTextsInCollection();

		// 3. Predict texts and store the result
		predictAndStoreResult();

		// 4. Write the result to our output file.
		writeResultToFile();
		
		System.out.println("Yeah, we are done. Check your output ("+OUTPUTFILE+") for your result.");
	}

	private void checkCollectionElseCreate() throws SaploClientException {

		System.out.println("Checking if user already has a Text Collection to store texts in.");

		/*
		 * First lets see if my account has any collections created for this
		 * purpose already. Get a list of all collections my account has access
		 * to. (http://developer.saplo.com/method/collection-list)
		 */
		List<SaploCollection> collectionList = client.getCollectionManager().list();

		/*
		 * The list contains all your collections but we want to get a specific
		 * one by its name.
		 */
		for (SaploCollection collection : collectionList) {
			if (collection.getName().equals(DEFAULT_COLLECTION_NAME)) {
				System.out
						.println("Yeah, You have probably run this before because I found a Text Collection that matched '"
								+ DEFAULT_COLLECTION_NAME + "'. I'll prepare and reset it for a new run.");

				this.collection = collection;

				/*
				 * Reset collection if it exists.
				 * (http://developer.saplo.com/method/collection-reset)
				 */
				client.getCollectionManager().reset(collection);

				return;
			}
		}

		/*
		 * No collection matched the name we were looking for so we will create
		 * a new one with the default name and for English texts. We persist the
		 * collection to Saplo API so it gets a generated id.
		 * (http://developer.saplo.com/method/collection-create)
		 */
		
		System.out.println("I see you are new here. I'll create a Text Collection you can use for storing texts.");
		this.collection = new SaploCollection(DEFAULT_COLLECTION_NAME, Language.en);
		client.getCollectionManager().create(collection);

		/*
		 * Tip: In a production environment we would store the generated
		 * collection id in a configuration file or variable so that we don't
		 * have to list the collections every time we wanted to use it.
		 */

	}

	private void readFileAndCreateTextsInCollection() throws IOException {
		System.out.println("Reading input file and adding texts to your Text Collection in Saplo API.");

		char separator = ",".charAt(0);
		char quotechar = "\"".charAt(0);
		CSVReader reader = new CSVReader(new FileReader(INPUTFILE), separator, quotechar);
		String[] nextLine;
		int count = 1;

		while ((nextLine = reader.readNext()) != null) {

			if (count > 20) {
				System.out.println("Stop file reading...this demo application is limited to 20 texts.");
				break;
			}
			count++;

			/*
			 * We create a PredictionText which contains of a text and a target
			 * word.
			 */
			PredictionText text = new PredictionText(collection, nextLine[0]);
			text.setTargetWord(nextLine[1]);

			/*
			 * Add the target word to a set since we want to use all target
			 * words later to find which texts to predict result for.
			 */
			uniqueTargetWords.add(nextLine[1]);

			try {

				/*
				 * Add text to Saplo API.
				 * 
				 * Tip: For production use this can be done using a batch call.
				 * (http://developer.saplo.com/topic/JSON-RPC)
				 */
				client.getTextManager().create((SaploText) text);

			} catch (SaploClientException e) {
				System.out.println("Unable to create text. (" + e.getErrorCode() + ") " + e.getMessage());
				text = null;
				continue;
			}

			texts.put(text.getId(), text);

		}

	}

	private void predictAndStoreResult() throws JSONException {

		System.out.println("Predicting sentiment.");
		
		/*
		 * To optimize the prediction time we use the method collection.predict.
		 * What it does is that it searches in a collection for all texts that
		 * contains the target_word. Since it is only possible to search one
		 * target_word at a time we iterate over all target words. The default
		 * texts in this application uses the same target word in all texts.
		 */
		for (String targetWord : uniqueTargetWords) {

			/*
			 * Build a custom JSON request since it is not yet standard in the
			 * Saplo4j client.
			 */
			JSONObject json = new JSONObject();
			json.put("collection_id", collection.getId());
			json.put("target_word", targetWord);
			json.put("model_id", modelId);
			json.put("wait", 30);

			JSONRPCRequestObject request = new JSONRPCRequestObject(0, "collection.predict", json);

			JSONRPCResponseObject response = null;

			try {
				// Do the request
				response = client.sendAndReceive(request);
			} catch (SaploClientException e) {
				System.out.println(":( An error occured with: (" + e.getErrorCode() + ") " + e.getMessage());
				System.exit(0);
			}
			if (response.getError() != null)
				continue;

			JSONArray jsonPredictions = ((JSONObject) response.getResult()).getJSONArray("predictions");

			/*
			 * Iterate over the result and set the value for every text we have.
			 */
			for (int i = 0; i < jsonPredictions.length(); i++) {
				JSONObject jo = jsonPredictions.getJSONObject(i);

				PredictionText text = texts.get(jo.get("text_id"));
				text.setValue(jo.get("value"));

			}

		}

	}

	private void writeResultToFile() throws IOException {

		System.out.println("Writing result to output file.");
		
		char separator = ",".charAt(0);
		char quotechar = "\"".charAt(0);
		CSVWriter writer = new CSVWriter(new FileWriter(OUTPUTFILE), separator, quotechar);

		for (Iterator<Integer> iterator = texts.keySet().iterator(); iterator.hasNext();) {
			PredictionText predictionText = texts.get(iterator.next());
			String[] arr = new String[4];
			arr[0] = String.valueOf(predictionText.getId());
			arr[1] = predictionText.getBody();
			arr[2] = predictionText.getTargetWord();
			arr[3] = String.valueOf(predictionText.getValue());

			writer.writeNext(arr);
		}
		writer.close();

	}

	class PredictionText extends SaploText {

		private Object value;
		private String targetWord;

		public PredictionText(SaploCollection collection, String body) {
			super(collection, body);
		}

		public Object getValue() {
			return value;
		}

		public void setValue(Object value) {
			this.value = value;
		}

		public String getTargetWord() {
			return targetWord;
		}

		public void setTargetWord(String targetWord) {
			this.targetWord = targetWord;
		}

	}

	/**
	 * @param args
	 * @throws IOException
	 * @throws SaploClientException
	 * @throws JSONException
	 * @throws InterruptedException
	 */
	public static void main(String[] args) throws SaploClientException, IOException, JSONException,
			InterruptedException {

		for (String string : args) {

			String[] split = string.split("=");

			if (split[0].equalsIgnoreCase("--apikey"))
				TrySentiment.APIKEY = split[1];

			if (split[0].equalsIgnoreCase("--secretkey"))
				TrySentiment.SECRETKEY = split[1];

			if (split[0].equalsIgnoreCase("--input"))
				TrySentiment.INPUTFILE = split[1];

			if (split[0].equalsIgnoreCase("--output"))
				TrySentiment.OUTPUTFILE = split[1];
		}

		boolean quit = false;

		if (TrySentiment.APIKEY == null || TrySentiment.APIKEY.isEmpty()) {
			System.out.println("Missing API Key (--apikey)");
			quit = true;
		}

		if (TrySentiment.APIKEY == null || TrySentiment.SECRETKEY.isEmpty()) {
			System.out.println("Missing Secret Key (--secretkey)");
			quit = true;
		}

		if (quit)
			System.exit(0);

		TrySentiment tryPrediction = new TrySentiment();
		tryPrediction.run();
	}

}
