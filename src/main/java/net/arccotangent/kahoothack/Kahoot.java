/*
kahoot-hack - Reverse engineering kahoot.it
Copyright (C) 2016-2019 Arccotangent

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as
published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package net.arccotangent.kahoothack;

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.CloseableHttpClient;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Random;
import java.util.Scanner;

class Kahoot extends Thread {

	private final String URL_BASE = "https://kahoot.it/cometd/";
	private String uname; //This Kahoot object's username
	private String client_id; //Unique client ID assigned to Kahoot clients
	private CloseableHttpClient cli; //HTTP client
	private String stoken; //This Kahoot object's decoded session token
	private String bayeux_cookie;
	private boolean active = false; //Whether this Kahoot object is connected to a game or not
	private int gameid; //The game pin
	private Scanner in; //A scanner hopefully scanning System.in
	private int gm; //This Kahoot object's operation mode. 1 = play normally, 2 = auto answer questions randomly
	private int lastanswer; //The last answer submitted to the game
	private int score; //Score from last question
	private int totalscore; //Total score
	private int rank; //This Kahoot object's rank
	private String nemesis; //The person directly ahead of us. If there is no one ahead of us (1st place) this will be set to "no one"
	private int nemesispoints;
	private boolean qa; //Question answered? prevents duplicate returns on getLastAnswerBlocking
	private boolean la2v = false; //Was 2 a valid answer on the last question?
	private boolean la3v = false; //Was 3 a valid answer on the last question?
	private int question; //Question number
	private boolean isTeam = false; //Is this a team game or classic PvP?
	private boolean twoFactorAuth = false; //Does this game include 2-factor auth?
	
	private static boolean debug = false; //Connection debug mode, not useful to regular users

	/**
	 * Construct a new Kahoot object. The newly constructed object can be thought of as a computer player.<br>
	 * This Kahoot object can act as a bot that automatically answers questions randomly.<br>
	 * It can also act as a regular player, depending on input from the user.
	 *
	 * @param username the username that this Kahoot object will use to connect to the game
	 * @param gamepin the Kahoot.it game PIN
	 * @param stdin a Scanner scanning System.in. If this parameter is not scanning System.in, expect bugs and even crashes.
	 * @param gamemode the gamemode. 1 = play normally, 2 = auto answer questions randomly, anything else is invalid and won't work
	 * @param instantActive whether the object should instantly be active (for a bot?). If unsure, set to false.
	 */
	public Kahoot(String username, int gamepin, Scanner stdin, int gamemode, boolean instantActive) {
		uname = username;
		in = stdin;
		gm = gamemode;
		active = instantActive;
		gameid = gamepin;
	}
	
	/**
	 * Start this Kahoot object in a new thread, making concurrency easier.
	 */
	public void run() {
		if (gm == 1) {
			//Auto initialization is unnecessary as this Kahoot object is registered as a regular user.
			this.login();
			System.out.println("You should be in game, see your name on screen?");
			this.play();
		} else if (gm == 2) {
			this.initialize();
			this.login();
			this.rand();
		}
		//Any other gamemode is invalid, so disconnect right after login.
		this.disconnect();
	}
	
	/**
	 * Check if debug mode is enabled.
	 * @return true if debug mode is enabled, false otherwise
	 */
	public static boolean isDebug() {
		return debug;
	}

	/**
	 * Check if answer 2 was a valid answer on the last question.
	 * @return true if answer 2 was valid, otherwise false
	 */
	public boolean wasLastQuestionAnswer2Valid() {
		return la2v;
	}

	/**
	 * Check if answer 3 was a valid answer on the last question.
	 * @return true if answer 3 was valid, otherwise false
	 */
	public boolean wasLastQuestionAnswer3Valid() {
		return la3v;
	}

	/**
	 * Check if this Kahoot object is in game. If the Kahoot object was ejected from the game for whatever reason, this will return false.
	 * @return true if the Kahoot object is in game, false otherwise
	 */
	public boolean gameRunning() {
		return active;
	}
	
	/**
	 * Get the current question number (ID)
	 * @return The current question number
	 */
	public int getQuestionID() {
		return question;
	}

	/**
	 * Get the last answer submitted to a question by this Kahoot object.<br>
	 *<br>
	 * WARNING: This function is blocking, meaning it will halt execution (unless this object is on its own thread) until a question is answered. This function is useful for counting answers submitted by the rand() function.<br>
	 *<br>
	 * This function can carry a delay of 10 milliseconds before it returns, but Kahoot questions take more than 1000 milliseconds to load, so this shouldn't be a problem.
	 * @return last answer submitted, returns -1 if this Kahoot object isn't in game for whatever reason
	 * @throws InterruptedException if sleep is interrupted
	 */
	public int getLastAnswerBlocking() throws InterruptedException {
		while (!qa) {
			//wait until qa is true
			Thread.sleep(10);
			if (!this.active)
				return -1; //halt if the game isn't active.
		}
		qa = false;
		return this.active ? lastanswer : -1;
	}

	/**
	 * Get the last answer submitted to a question by this Kahoot object.<br>
	 * <br>
	 * WARNING: This function is non-blocking, meaning it can return the same answer for the same question twice. If you want a blocking function, check getLastAnswerBlocking()
	 * @return last answer submitted, returns -1 if this Kahoot object isn't in game for whatever reason
	 */
	public int getLastAnswer() {
		qa = false;
		return this.active ? lastanswer : -1;
	}
	
	/**
	 * Get the score from the last question answered
	 * @return score gained from last answered question
	 */
	public int getLastScore() {
		return score;
	}

	/**
	 * Get your total score between all questions
	 * @return total score
	 */
	public int getTotalScore() {
		return totalscore;
	}

	/**
	 * Get your rank
	 * @return your rank
	 */
	public int getRank() {
		return rank;
	}

	/**
	 * Get this Kahoot object's nemesis, or the player directly ahead of it.
	 * @return the player directly ahead of this Kahoot object
	 */
	public String getNemesis() {
		return nemesis;
	}
	
	/**
	 * Check whether this game is a team game or not
	 * @return true if team game, false if classic PvP
	 */
	public boolean isTeamGame() {
		return isTeam;
	}
	
	/**
	 * Check whether game has 2 factor auth enabled
	 * @return true if 2FA enabled, false otherwise
	 */
	public boolean is2FAGame() {
		return twoFactorAuth;
	}

	/**
	 * Initialize this Kahoot object and connect to the server.<br>
	 * This function does the same thing as if you were to enter the game PIN on the Kahoot website, but it does not log you in.<br>
	 *<br>
	 * This function must be called regardless of whether the bot will be allowed to manage itself or not. Check the run() documentation for more information.
	 */
	public void initialize() {
		stoken = Session.getSessionToken(gameid);
		stoken = Session.decodeSessionToken(stoken);
		
		isTeam = Session.getLastGameTeam();
		twoFactorAuth = Session.getLastGame2FA();
		
		cli = HTTP.getClient();
		
		if (debug) {
			System.out.println("stoken = " + stoken);
			System.out.println("gameid = " + gameid);
		}

		JSONObject advice = new JSONObject();
		advice.put("timeout", 60000);
		advice.put("interval", 0);

		JSONObject k = new JSONObject();
		k.put("advice" , advice.toString());
		k.put("version", "1.0");
		k.put("minimumVersion", "1.0");
		k.put("channel", "/meta/handshake");

		JSONArray supportedConnTypes = new JSONArray();
		supportedConnTypes.put("long-polling");

		k.put("supportedConnectionTypes", supportedConnTypes.toString());

		HttpPost p = HTTP.POST(URL_BASE + gameid + "/" + stoken + "/handshake", k.toString());
		try {
			CloseableHttpResponse res = cli.execute(p);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			Header[] h = res.getAllHeaders();
			for (int i = 0; i < h.length; i++) {
				if (h[i].getName().equalsIgnoreCase("Set-Cookie")) {
					bayeux_cookie = h[i].getValue();
					int sc = bayeux_cookie.lastIndexOf(';');
					bayeux_cookie = bayeux_cookie.substring(0, sc);
				}
			}
			if (debug)
				System.out.println("1 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			client_id = r.getString("clientId");
		} catch (IOException e) {
			e.printStackTrace();
		}

		//BEGIN STAGE 2

		JSONObject c = new JSONObject();
		c.put("channel", "/meta/unsubscribe");
		c.put("clientId", client_id);
		//c.put("connectionType", "long-polling");
		c.put("subscription", "/service/controller");

		HttpPost p2 = HTTP.POST(URL_BASE + gameid + "/" + stoken, c.toString());
		p2.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p2);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("2 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[STAGE 2] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		//BEGIN STAGE 3

		JSONObject c2 = new JSONObject();
		c2.put("channel", "/meta/connect");
		c2.put("clientId", client_id);
		c2.put("connectionType", "long-polling");

		HttpPost p3 = HTTP.POST(URL_BASE + gameid + "/" + stoken + "/connect", c2.toString());
		p3.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p3);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("3 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[STAGE 3] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		//BEGIN STAGE 4

		JSONObject c6 = new JSONObject();
		c6.put("channel", "/meta/subscribe");
		c6.put("clientId", client_id);
		//c6.put("connectionType", "long-polling");
		c6.put("subscription", "/service/status");

		HttpPost p7 = HTTP.POST(URL_BASE + gameid + "/" + stoken, c6.toString());
		p7.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p7);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("4-1 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[STAGE 4/SERVICE_STATUS] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		JSONObject c7 = new JSONObject();
		c7.put("channel", "/meta/subscribe");
		c7.put("clientId", client_id);
		//c7.put("connectionType", "long-polling");
		c7.put("subscription", "/service/player");

		HttpPost p8 = HTTP.POST(URL_BASE + gameid + "/" + stoken, c7.toString());
		p8.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p8);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("4-2 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[STAGE 4/SERVICE_PLAYER] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		JSONObject c8 = new JSONObject();
		c8.put("channel", "/meta/subscribe");
		c8.put("clientId", client_id);
		//c8.put("connectionType", "long-polling");
		c8.put("subscription", "/service/controller");

		HttpPost p9 = HTTP.POST(URL_BASE + gameid + "/" + stoken, c8.toString());
		p9.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p9);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("4-3 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[STAGE 4/SERVICE_CONTROLLER] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Login to the Kahoot game.<br>
	 * This function is does the same thing as if you were to enter your nickname in the second screen you would see in your browser.
	 */
	public void login() {
		JSONObject c = new JSONObject();
		c.put("channel", "/service/controller");
		c.put("clientId", client_id);
		//c.put("id", "62");

		JSONObject data = new JSONObject();
		data.put("type", "login");
		data.put("gameid", gameid);
		data.put("host", "kahoot.it");
		data.put("name", uname);

		c.put("data", data);

		HttpPost p = HTTP.POST(URL_BASE + gameid + "/" + stoken, c.toString());
		p.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("L1 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(r2.length() - 1);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[LOGIN/BEGIN] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		String req = "{\"clientId\":\"" + client_id + "\",\"channel\":\"/meta/connect\",\"connectionType\":\"long-polling\"}";

		HttpPost p2 = HTTP.POST(URL_BASE + gameid + "/" + stoken + "/connect", req);
		p2.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p2);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("L2 = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(r2.length() - 1);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[LOGIN/FINISH] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		active = true;
	}

	/**
	 * Disconnect from the Kahoot game.<br>
	 * It is possible to rejoin the Kahoot game if this function is called before the game is over.
	 */
	public void disconnect() {
		JSONObject c = new JSONObject();
		c.put("channel", "/meta/disconnect");
		c.put("clientId", client_id);

		HttpPost p = HTTP.POST(URL_BASE + gameid + "/" + stoken + "/disconnect", c.toString());
		p.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("D = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(0);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[DISCONNECT] Error connecting to server! Full server response below.");
				System.out.println(response);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}

		active = false;
	}

	private void answerQuestion(int ans) {
		JSONObject device = new JSONObject();
		device.put("userAgent", HTTP.uagent);

		JSONObject screen = new JSONObject();
		screen.put("width", 1337);
		screen.put("height", 1337);

		device.put("screen", screen);

		JSONObject meta = new JSONObject();
		meta.put("lag", 22);
		meta.put("device", device);

		JSONObject content = new JSONObject();
		content.put("choice", ans);
		content.put("meta", meta);

		JSONObject data = new JSONObject();
		data.put("id", 6);
		data.put("type", "message");
		data.put("gameid", gameid);
		data.put("host", "kahoot.it");
		data.put("content", content.toString());
		//data.put("connectionType", "long-polling");

		JSONObject base = new JSONObject();
		base.put("clientId", client_id);
		base.put("channel", "/service/controller");
		base.put("data", data);
		
		HttpPost p = HTTP.POST(URL_BASE + gameid + "/" + stoken, base.toString());
		p.setHeader("Cookie", bayeux_cookie);
		try {
			CloseableHttpResponse res = cli.execute(p);
			BasicResponseHandler handler = new BasicResponseHandler();
			String response = handler.handleResponse(res);
			if (debug)
				System.out.println("AQ = " + response);
			JSONArray r2 = new JSONArray(response);
			JSONObject r = r2.getJSONObject(r2.length() - 1);
			boolean success = r.getBoolean("successful");
			if (!success) {
				System.out.println("[QUESTION/ANSWER] Error connecting to server! Full server response below.");
			}
			qa = true;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Automatically answer questions with a randomly chosen answer.<br>
	 * This function does the same thing as unixpickle's kahoot-rand program.<br>
	 * <br>
	 * <a href="https://github.com/unixpickle/kahoot-hack">unixpickle's Kahoot hack</a>
	 */
	public void rand() {
		gm = 2;
		while (active) {
			JSONObject c2 = new JSONObject();
			c2.put("channel", "/meta/connect");
			c2.put("connectionType", "long-polling");
			c2.put("clientId", client_id);

			HttpPost p2 = HTTP.POST(URL_BASE + gameid + "/" + stoken + "/connect", c2.toString());
			p2.setHeader("Cookie", bayeux_cookie);
			try {
				CloseableHttpResponse res = cli.execute(p2);
				BasicResponseHandler handler = new BasicResponseHandler();
				String response = handler.handleResponse(res);
				if (debug)
					System.out.println("R = " + response);
				JSONArray r2 = new JSONArray(response);
				JSONObject r = r2.getJSONObject(r2.length() - 1);
				if (response.contains("kick")) {
					System.out.println("Bot with username '" + uname + "' was kicked from the game!");
					active = false;
				}
				boolean success = r.getBoolean("successful");
				if (!success) {
					System.out.println("[LOGIN/FINISH] Error connecting to server! Full server response below.");
					System.out.println(response);
				}

				if (response.contains("answerMap") && !response.contains("timeLeft")) {
					JSONObject a = r2.getJSONObject(0);
					JSONObject data = a.getJSONObject("data");
					JSONObject content = new JSONObject(data.getString("content").replace("\\", ""));
					JSONObject answers = content.getJSONObject("answerMap");
					if (answers.length() == 2) {
						la2v = false;
						la3v = false;
					}
					if (answers.length() == 3) {
						la2v = true;
						la3v = false;
					}
					if (answers.length() == 4) {
						la2v = true;
						la3v = true;
					}
					Random rng = new Random();
					question = content.getInt("questionIndex") + 1;
					int answer = rng.nextInt(answers.length());
					int ranswer = answers.getInt(Integer.toString(answer));
					this.answerQuestion(ranswer);
					lastanswer = answer;
				} else if (response.contains("quizId")) {
					active = false;
				}

				Thread.sleep(50);
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * Play the game as if you were playing it in your browser.<br>
	 * Answers are organized in the following fashion:<br>
	 * <br>
	 * 0 1<br>
	 * 2 3<br>
	 * <br>
	 * 2 and 3 are only there if the question has them.
	 */
	public void play() {
		gm = 1;
		while (active) {
			JSONObject c2 = new JSONObject();
			c2.put("channel", "/meta/connect");
			c2.put("connectionType", "long-polling");
			c2.put("clientId", client_id);

			HttpPost p2 = HTTP.POST(URL_BASE + gameid + "/" + stoken + "/connect", c2.toString());
			p2.setHeader("Cookie", bayeux_cookie);
			try {
				CloseableHttpResponse res = cli.execute(p2);
				BasicResponseHandler handler = new BasicResponseHandler();
				String response = handler.handleResponse(res);
				if (debug)
					System.out.println("P = " + response);
				JSONArray r2 = new JSONArray(response);
				JSONObject r = r2.getJSONObject(r2.length() - 1);
				JSONObject a = r2.getJSONObject(0);
				if (response.contains("kick")) {
					System.out.println("You were kicked from the game!");
					active = false;
				}
				boolean success = r.getBoolean("successful");
				if (!success) {
					System.out.println("[LOGIN/FINISH] Error connecting to server! Full server response below.");
					System.out.println(response);
				}
				if (response.contains("answerMap") && !response.contains("timeLeft")) {
					JSONObject data = a.getJSONObject("data");
					JSONObject content = new JSONObject(data.getString("content").replace("\\", ""));
					JSONObject answers = content.getJSONObject("answerMap");
					if (answers.length() == 2) {
						la2v = false;
						la3v = false;
					} else if (answers.length() == 3) {
						la2v = true;
						la3v = false;
					} else {
						la2v = true;
						la3v = true;
					}
					question = content.getInt("questionIndex") + 1;
					System.out.println("Answers for question " + question + ": 0 through " + (answers.length() - 1));
					System.out.print("Answer: ");
					int ans = in.nextInt();
					lastanswer = ans;
					int ra = answers.getInt(Integer.toString(ans));
					this.answerQuestion(ra);
				} else if (response.contains("answerMap")) {
					System.out.println("Get ready, question is coming up!");
				}

				if (response.contains("primaryMessage")) {
					JSONObject d = a.getJSONObject("data");
					JSONObject c = new JSONObject(d.getString("content").replace("\\", ""));
					String msg = c.getString("primaryMessage");
					System.out.println(msg);
				} else if (response.contains("isCorrect")) {
					JSONObject d = a.getJSONObject("data");
					JSONObject c = new JSONObject(d.getString("content").replace("\\", ""));
					boolean correct = c.getBoolean("isCorrect");
					score = c.getInt("points");
					totalscore = c.getInt("totalScore");
					rank = c.getInt("rank");
					JSONObject rawnemesis = c.getJSONObject("nemesis");
					if (rawnemesis == null || rawnemesis == JSONObject.NULL) {
						nemesis = "no one";
						nemesispoints = score;
					} else {
						nemesis = rawnemesis.getString("name");
						nemesispoints = rawnemesis.getInt("totalScore");
					}
					System.out.println(correct ? "Correct!" : "Incorrect.");
					System.out.println("You got " + score + " points from that question");
					System.out.println("You currently have " + totalscore + " points");
					System.out.println("You are in rank " + rank + ", behind " + nemesis + ". Nemesis has " + nemesispoints + " points.");
				} else if (response.contains("quizId")) {
					JSONObject d = a.getJSONObject("data");
					JSONObject c = new JSONObject(d.getString("content").replace("\\", ""));
					String quizid = c.getString("quizId");
					int playercount = c.getInt("playerCount");
					System.out.println("This quiz's ID is " + quizid);
					System.out.println("Players in game: " + playercount);
					active = false;
				}

				Thread.sleep(50);
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}
		}

		System.out.println("Game over!");
	}

}