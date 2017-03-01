package com.github.atramos.quant.etrade_tools;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.apache.commons.collections4.ListUtils;
import org.apache.commons.io.FileUtils;

import com.etrade.etws.account.Account;
import com.etrade.etws.account.AccountListResponse;
import com.etrade.etws.market.DetailFlag;
import com.etrade.etws.market.OptionChainPair;
import com.etrade.etws.market.OptionChainRequest;
import com.etrade.etws.market.OptionChainResponse;
import com.etrade.etws.market.QuoteResponse;
import com.etrade.etws.oauth.sdk.client.IOAuthClient;
import com.etrade.etws.oauth.sdk.client.OAuthClientImpl;
import com.etrade.etws.oauth.sdk.common.Token;
import com.etrade.etws.sdk.client.AccountsClient;
import com.etrade.etws.sdk.client.ClientRequest;
import com.etrade.etws.sdk.client.Environment;
import com.etrade.etws.sdk.client.MarketClient;
import com.etrade.etws.sdk.common.ETWSException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.atramos.quant.universe.SP500;
import com.github.atramos.quant.universe.Universe;

/**
 * E-Trade API tutorial code, heavily debugged, refactored and expanded.
 * Original code from:
 * https://us.etrade.com/ctnt/dev-portal/getContent?contentUri=V0_Code-Tutorial
 * 
 * Sandbox tokens:
 * https://us.etrade.com/ctnt/dev-portal/getContent?contentUri=V0_Documentation-
 * DeveloperGuides-Authorization
 * 
 * @author atram
 *
 */
public class Downloader {
	
	private Logger logger = Logger.getLogger(getClass().getName());
	
	private IOAuthClient oauth_client = OAuthClientImpl.getInstance();

	private DataAccess da = new DataAccess();

	public Downloader() throws JsonProcessingException, IOException {
	}

	private JsonNode config = new ObjectMapper().readTree(new File("src/main/resources/config.json"))
			.get(getClass().getSimpleName());

	private String oauth_consumer_key = config.get("oauth_consumer_key").asText();

	private String oauth_consumer_secret = config.get("oauth_consumer_secret").asText();

	private String oauth_access_token = null;

	private String oauth_access_token_secret = null;

	private String oauth_request_token;

	private String oauth_request_token_secret;

	private Environment env = Environment.valueOf(config.get("environment").asText());

	private File cookieFile = new File("cookie.txt");

	private void authorize() throws IOException, ETWSException, URISyntaxException {
		if (cookieFile.exists())
			return;
		ClientRequest request = new ClientRequest();
		request.setEnv(env);
		request.setConsumerKey(oauth_consumer_key);
		request.setConsumerSecret(oauth_consumer_secret);
		Token token = oauth_client.getRequestToken(request);
		oauth_request_token = token.getToken();
		oauth_request_token_secret = token.getSecret();
		request.setToken(oauth_request_token);
		request.setTokenSecret(oauth_request_token_secret);
		String authorizeURL = oauth_client.getAuthorizeUrl(request);
		URI uri = new java.net.URI(authorizeURL);
		Desktop desktop = Desktop.getDesktop();
		desktop.browse(uri);
	}

	private void verify() throws IOException, ETWSException {
		if (!cookieFile.exists()) {
			System.out.print("ENTER THE CODE AND PRESS ENTER: ");
			System.out.flush();
			String oauth_verify_code = new BufferedReader(new InputStreamReader(System.in)).readLine();
			ClientRequest request = newClientRequest();
			request.setToken(oauth_request_token);
			request.setTokenSecret(oauth_request_token_secret);
			request.setVerifierCode(oauth_verify_code); // Set verification code
			// Get access token
			Token token = oauth_client.getAccessToken(request); // Get
																// access-token
			// object
			oauth_access_token = token.getToken(); // Access token string
			oauth_access_token_secret = token.getSecret(); // Access token
															// secret
			FileUtils.writeLines(cookieFile,
					Arrays.asList(new String[] { oauth_access_token, oauth_access_token_secret }));
		} else {
			System.out.println("using cookie file " + cookieFile.getAbsolutePath());
			List<String> lines = FileUtils.readLines(cookieFile);
			oauth_access_token = lines.get(0);
			oauth_access_token_secret = lines.get(1);
		}
	}

	public void listAccounts() {
		ClientRequest request = newClientRequest();
		try {
			AccountsClient account_client = new AccountsClient(request);
			AccountListResponse response = account_client.getAccountList();
			List<Account> alist = response.getResponse();
			Iterator<Account> al = alist.iterator();
			while (al.hasNext()) {
				Account a = al.next();
				System.out.println("===================");
				System.out.println("Account: " + a.getAccountId());
				System.out.println("===================");
			}
		} catch (Exception e) {
		}
	}

	private ClientRequest newClientRequest() {
		ClientRequest request = new ClientRequest();
		// Prepare request
		request.setEnv(env);
		request.setConsumerKey(oauth_consumer_key);
		request.setConsumerSecret(oauth_consumer_secret);
		request.setToken(oauth_access_token);
		request.setTokenSecret(oauth_access_token_secret);
		return request;
	}

	private List<OptionChainPair> getOptionChain(String symbol, String month, String year) throws IOException, ETWSException {
		OptionChainRequest req = new OptionChainRequest();
		req.setExpirationMonth(month);
		req.setExpirationYear(year);
		req.setChainType("CALLPUT");
		req.setSkipAdjusted("TRUE");
		req.setUnderlier(symbol);
		ClientRequest request = newClientRequest();
		MarketClient client = new MarketClient(request);
		try {
			OptionChainResponse response = client.getOptionChain(req);
			return response.getOptionPairs();
		} catch (Exception e) {
			logger.fine("No quote: " + symbol + "." + month + "." + year);
			return new ArrayList<>();
		}
	}

	private void getQuotes(List<String> symbols) throws IOException, ETWSException {
		logger.info(symbols.size() + " Symbols: " + symbols);
		ClientRequest request = newClientRequest();
		MarketClient client = new MarketClient(request);
		for(List<String> part : ListUtils.partition(symbols, 25)) {
			logger.info(part.toString());
			QuoteResponse response = client.getQuote(new ArrayList<String>(part), Boolean.FALSE, DetailFlag.INTRADAY);
			da.store(response.getQuoteData(), quote -> quote.getProduct().getSymbol(), true);
		}
	}

	private void getOptionQuotes(List<String> symbols) throws IOException, ETWSException {
		ClientRequest request = newClientRequest();
		MarketClient client = new MarketClient(request);
		for(List<String> part : ListUtils.partition(symbols, 25)) {
			logger.info(part.toString());
			QuoteResponse response = client.getQuote(new ArrayList<String>(part), Boolean.FALSE, DetailFlag.INTRADAY);
			da.store(response.getQuoteData(), 
					quote -> quote.getProduct().getSymbol()
						+ ":" + quote.getProduct().getExpirationYear()
						+ ":" + quote.getProduct().getExpirationMonth()
						+ ":" + quote.getProduct().getExpirationDay()
						+ ":" + quote.getProduct().getOptionType()
						+ ":" + quote.getProduct().getStrikePrice(), true);
		}
	}

	public static void main(String[] args) throws Exception {
		Downloader t = new Downloader();
		t.authorize();
		t.verify();
		t.downloadAll();
	}
	
	private static Universe universe = new SP500(); 

	private void downloadAll() throws JsonProcessingException, IOException, ETWSException {
		
		da.deleteFromView("removal_q");
		
		List<String> stocks = universe.listStocks();
		Set<String> stopList = da.getDocIndex().keySet();
		
		stocks.removeAll(stopList);
		this.getQuotes(stocks);
		
		List<String> topByVolume = da.getTopByVolume(100);
		logger.info("Top Stocks = " + topByVolume);
		
		for(String exp: new String[] { "2017-03", "2017-04", "2017-05", 
				"2017-09", "2017-12", "2018-04"}) {
			
			for(String sym: topByVolume) {
				String[] year_month = exp.split("-");
				String key = sym + ".chain." + exp;

				if(stopList.contains(key)) continue;
				
				da.store(getOptionChain(sym, year_month[1], year_month[0]), key, false);
			}
		}
			
		List<String> queue = da.getOptionQuoteQueue();
		
		this.getOptionQuotes(queue);
	}

}
