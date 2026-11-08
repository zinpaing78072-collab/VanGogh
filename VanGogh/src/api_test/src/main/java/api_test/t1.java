package api_test;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.net.URI;

public class t1 
{
	public static void main(String[] args) 
	{
		HttpRequest post_req = HttpRequest.newBuilder()
				.uri(URI.create("https://api.thegamesdb.net/v1/Games/ByGameID?apikey="+api.API_KEY+"&id="+api.gameid+"&fields=overview,rating,genres,publishers,players&include=boxart,platform"))

				.header("Accept", "application/json")
				
			    .GET()

			    .build();
		
		HttpClient post_actp = HttpClient.newHttpClient();
		
			try 
			{
				HttpResponse<String> respon = post_actp.send(post_req, BodyHandlers.ofString());
				
				JsonObject json = JsonParser.parseString(respon.body()).getAsJsonObject();
				
				Path path = Path.of("src\\api_test\\src\\json_cache\\cashe.json");

				int count = 1;

				while (Files.exists(path)) {
				    path = Path.of("src\\api_test\\src\\json_cache\\cashe" + count + ".json");
				    count++;
				}

				Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(json));
				
				System.out.println(json);
				
			} 
			catch (IOException | InterruptedException e) 
			{
				e.printStackTrace();
			}
	}
}
