package APIHandler;

import Default.Controller;
import Default.DatabaseHandler;
import Default.Main;
import javafx.scene.control.ProgressBar;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.*;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * @author Luke K. Rose <psylr5@nottingham.ac.uk>
 * @version 1.0
 * @since 0.1
 */

public class INTRINIOHandler {
    private static final String INTRINIO_CSV_CALL = "https://api.intrinio.com/news.csv?page_size=10000&ticker=";
    static private String INTRINIO_USERNAME;
    static private String INTRINIO_PASSWORD;
    static private final int PAGES = 0, ARTICLES = 1 /*Indices for accessing JSON metadata*/;
    static private int DOWNLOAD_THREADS = 1;
    static private DatabaseHandler dh;
    static private ProgressBar pb;
    static private double progress = 0;

    /**
     * Authenticates the application with the INTRINIO server using the user's username and password, to access news records
     *
     * @param username INTRINIO account username
     * @param password INTRINIO account password
     * @see <a href="https://intrinio.com/signup">Sign up for a free API account</a>
     */
    static public void authenticate(String username, String password){
        INTRINIO_USERNAME = username;
        INTRINIO_PASSWORD = password;

        Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(INTRINIO_USERNAME,INTRINIO_PASSWORD.toCharArray());
            }
        });
    }

    /**
     * Downloads all news articles available for a given stock and saves it to the database
     *
     * @param stock Stock to download news for
     * @throws IOException          Throws IOException if the API request fails due to server unavailability or connection refusal
     * @throws SQLException         Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     * @throws InterruptedException Throws InterruptedException if the sleep function is interrupted by another process
     */
    private static void getHistoricNews(String stock) throws IOException, SQLException, InterruptedException {
        if (isOverLimit(0)) return;

        int values[] = getCSVMetaData(stock);
        int storedArticles = Integer.parseInt(dh.executeQuery("SELECT COUNT(*) FROM newsarticles WHERE Symbol='" + stock + "';").get(0));
        int missingArticles = values[ARTICLES] - storedArticles;

        Main.getController().updateCurrentTask("MISSING ARTICLES FOR '" + stock + "': " + missingArticles, false, false);

        if (missingArticles < 0)
            Main.getController().updateCurrentTask("NEGATIVE MISSING ARTICLE VALUE - May be due to API inaccessibility", true, false);
        if (missingArticles <= 0) return;

        int i = 1;

        while (i <= values[PAGES] && missingArticles > 0) missingArticles -= getCSVNews(stock, i++, missingArticles);

        if (missingArticles > 0) Main.getController().updateCurrentTask("DID NOT DOWNLOAD ALL ARTICLES", true, false);
    }

    /**
     * Initialises the INTRINIOHandler class by setting the necessary DatabaseHandler and ProgressBar
     *
     * @param nddh News Downloader {@link DatabaseHandler} required to access the database without causing a deadlock
     * @param pb   {@link ProgressBar} to show the progress of new downloads
     */
    static public void initialise(DatabaseHandler nddh, ProgressBar pb) throws SQLException {
        dh = nddh;
        INTRINIOHandler.pb = pb;

        DOWNLOAD_THREADS = Integer.parseInt(dh.executeQuery("SELECT COALESCE(value, 1) FROM settings WHERE ID ='NEWS_ARTICLE_PARALLEL_DOWNLOAD';").get(0));

        Main.getController().updateCurrentTask("Initialised News API Handler", false, false);
    }

    /**
     * Retrieves the current amount of calls used for the INTRINIO API from the database Today
     *
     * @return The amount of calls made to the INTRINIO API Today
     * @throws SQLException Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     */
    private static int getCurrentCalls() throws SQLException {
        ArrayList<String> sCalls = dh.executeQuery("SELECT Calls FROM apicalls WHERE Date = CURDATE() AND Name='INTRINIO';");

        if (!sCalls.isEmpty()) return Integer.parseInt(sCalls.get(0));

        return 0;
    }

    /**
     * Determines if any more API calls can be made Today
     *
     * @param callsToPerform The amount of calls that need to be made (usually 1 if accessing the API serially)
     * @return True if the API limit has been exceeded for Today, false otherwise
     * @throws SQLException Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     */
    private static boolean isOverLimit(int callsToPerform) throws SQLException {
        int limit = Integer.parseInt(dh.executeQuery("SELECT DailyLimit FROM apimanagement WHERE Name='INTRINIO';").get(0));

        return (callsToPerform + getCurrentCalls()) >= limit;
    }

    /**
     * Downloads the news statistics related to the given stock, including amount of articles available
     *
     * @param stock Stock ticker to download the metadata for (e.g. AAPL for Apple Inc.)
     * @return Integer array containing number of articles available and the amount of pages the articles are split across (with 10,000 articles per page)
     * @throws IOException          Throws IOException if the API request fails due to server unavailability or connection refusal
     * @throws SQLException         Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     * @throws InterruptedException Throws InterruptedException if the sleep function is interrupted by another process
     */
    private static int[] getCSVMetaData(String stock) throws IOException, SQLException, InterruptedException {
        URL url = new URL(INTRINIO_CSV_CALL + stock);

        TimeUnit.MILLISECONDS.sleep(1000); // To prevent blocking

        URLConnection connect = url.openConnection();
        InputStreamReader isr = null;

        try {
            isr = new InputStreamReader(url.openStream());
        } catch (IOException e) {
            HttpURLConnection http = (HttpURLConnection) connect;
            if (http.getResponseCode() == 429) Main.getController().updateCurrentTask("Too many requests", true, false);

            ((HttpURLConnection) connect).disconnect();

            dh.executeCommand("INSERT INTO apicalls VALUES ('INTRINIO', CURDATE(), 500) ON DUPLICATE KEY UPDATE Calls = 500;"); //Incase another system uses this program, this database value doesn't get updated, in which case if an error occurs, mark the api as "limit reached"
        }

        if (isr == null) return new int[]{0, 0};

        BufferedReader br = new BufferedReader(isr);
        String curr;
        curr=br.readLine();
        String[] splitString = curr.split(",");

        dh.executeCommand("INSERT INTO apicalls VALUES('INTRINIO', CURDATE(), 1) ON DUPLICATE KEY UPDATE Calls = Calls +1;");

        int pages = Integer.parseInt(splitString[3].split(":")[1].trim());
        int articles = Integer.parseInt(splitString[0].split(":")[1].trim());

        return new int[]{pages, articles};
    }

    /**
     * Downloads the news articles for a given stock in Comma Separated Value (CSV) file format
     *
     * @param stock           Stock ticker to download news articles for (e.g. AAPL for Apple Inc.)
     * @param page            The page number to access
     * @param missingArticles The number of articles to download from the API
     * @return The remaining number articles to download after this API call
     * @throws IOException          Throws IOException if the API request fails due to server unavailability or connection refusal
     * @throws SQLException         Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     * @throws InterruptedException Throws InterruptedException if the sleep function is interrupted by another process
     */
    private static int getCSVNews(String stock, int page, int missingArticles) throws IOException, SQLException, InterruptedException {
        Main.getController().updateCurrentTask("Getting headlines for " + stock + " (Page " + page + ")", false, false);
        URL url = new URL(INTRINIO_CSV_CALL + stock + "&page_number=" + page);

        TimeUnit.MILLISECONDS.sleep(1000);
        URLConnection connect = url.openConnection();
        InputStreamReader isr = null;

        try {
            isr = new InputStreamReader(url.openStream());
        } catch (IOException e) {
            HttpURLConnection http = (HttpURLConnection) connect;
            if (http.getResponseCode() == 429)
                System.err.println("Too many requests");

            ((HttpURLConnection) connect).disconnect();

            dh.executeCommand("INSERT INTO apicalls VALUES ('INTRINIO', CURDATE(), 500) ON DUPLICATE KEY UPDATE Calls = 500;"); //Incase another system uses this program, this database value doesn't get updated, in which case if an error occurs, mark the api as "limit reached"
        }

        if (isr == null) {
            Main.getController().updateCurrentTask("Could not connect URL Stream", false, false);
            return -1;
        }

        BufferedReader br = new BufferedReader(isr);
        String curr;

        ArrayList<String> newsArray = new ArrayList<>();

        Main.getController().updateCurrentTask("Downloading & Reading '" + stock + "' PAGE " + page + " news file...", false, false);

        for (int i = 0; i < 2; i++) br.readLine(); //Remove preamble

        while ((curr = br.readLine()) != null) newsArray.add(curr.replace("'", "").replace("`", "").replace("\"", ""));

        br.close();

        Main.getController().updateCurrentTask("Sorting '" + stock + "' PAGE " + page + " news file into chronological order...", false, false);

        //Preprocess news data to remove corrupted entries
        Set<String> newsSet = new LinkedHashSet<>(); //Linked hashset retains insertion order and removes duplicates
        Main.getController().updateCurrentTask("Cleaning '" + stock + "' PAGE " + page + " news file...", false, false);

        for (String news : newsArray) {
            String[] splitString = news.split(",");
            if (splitString.length >= 7) {
                //Usual case: 1.STOCK, 2 TICKER, 3 CODE,4 HEADLINE, 5 DATE, 6 URL, 7 DESCRIPTION
                news = splitString[0].replace(",", "") + "," + splitString[1].replace(",", "") + "," + splitString[2] + ",";
                ///////////////// START CLEANING:
                int i = 3;
                StringBuilder headline = new StringBuilder();
                String date;
                String link;

                while (i < splitString.length && !(splitString[i].matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\s.\\d{4}") || splitString[i].matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}")))
                    headline.append(splitString[i++].replace(",", ""));

                if (i < splitString.length) {
                    date = splitString[i++];
                    link = splitString[i++];
                    news += headline + "," + date + "," + link + ",";

                    StringBuilder newsBuilder = new StringBuilder(news);
                    for (; i < splitString.length; i++)
                        newsBuilder.append(splitString[i]);
                    news = newsBuilder.toString();
                }

                ///////////END CLEANING
            }
            String[] fixedSplit = news.split(",");

            if (fixedSplit.length == 6)
                if (fixedSplit[5].contains("http"))
                    news += "NULL";

            if (news.split(",").length == 7) {
                if (!(news.split(",")[4].matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\s.\\d{4}") || news.split(",")[4].matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}")))
                    Main.getController().updateCurrentTask("NO DATE FOUND IN: " + news, true, false);
                newsSet.add(news);
            }
        }

        newsArray.clear();

        int downloaded = 0;
        int newsSize = newsSet.size();

        Main.getController().updateCurrentTask("'" + stock + "' PAGE " + page + " WITH " + newsSize + " (Missing " + missingArticles + " articles)", false, false);

        dh.setAutoCommit(false);

        for (String news : newsSet) {
            String[] splitNews = news.split(",");
            String title = splitNews[3];
            String summary = splitNews[6];
            String date = splitNews[4];
            String link = splitNews[5];
            date = date.split(" ")[0] + " " + date.split(" ")[1];

            if (!date.matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}"))
                Main.getController().updateCurrentTask("NO DATE FOUND IN: " + news, false, false);

            String data = "'" + stock + "','" + title + "','" + summary + "','" + date + "','" + link + "'";
            String query = "SELECT 1 FROM newsarticles WHERE Symbol='" + stock + "' AND Headline='" + title + "' AND Published='" + date + "' AND URL ='" + link + "';";
            ArrayList<String> result = dh.executeQuery(query);

            if (result.isEmpty()) {
                Main.getController().updateCurrentTask("Discovered News Article for " + stock + ": " + title, false, false);
                String command;

                command = "INSERT INTO newsarticles (Symbol, Headline,Description,Published,URL,Duplicate) VALUES (" + data + ", (SELECT COALESCE((SELECT * FROM (SELECT 1 FROM newsarticles WHERE Symbol='" + stock + "' AND (Headline='" + title + "' OR URL='" + link + "') LIMIT 1) as t),0)));";

                dh.addBatchCommand(command);

                missingArticles--;
                downloaded++;

                if (missingArticles == 0) return downloaded;
            }
        }

        dh.executeBatch();
        dh.setAutoCommit(true);

        return downloaded;
    }

    /**
     * Downloads the content of news articles for all articles that are missing their main body from the database, given the URL retrieved from {@link APIHandler.INTRINIOHandler#getCSVNews(String, int, int)}
     *
     * @throws SQLException         Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     * @throws InterruptedException Throws InterruptedException if the sleep function is interrupted by another process
     */
    public static void downloadArticles() throws SQLException, InterruptedException {
        Main.getController().updateCurrentTask("Downloading missing news article content...", false, false);
        Controller.updateProgress(ProgressBar.INDETERMINATE_PROGRESS, pb);
        ArrayList<String> undownloadedArticles = dh.executeQuery("SELECT ID, URL FROM newsarticles WHERE Content IS NULL AND Blacklisted = 0 AND Redirected = 0 AND Duplicate = 0 AND URL != \"\";");

        if (undownloadedArticles == null || undownloadedArticles.isEmpty()) {
            Controller.updateProgress(0, pb);
            return;
        }

        Semaphore availableThreads = new Semaphore(DOWNLOAD_THREADS, false);

        double t = undownloadedArticles.size() - 1;
        progress = 0;

        for (String article : undownloadedArticles) {
            availableThreads.acquireUninterruptibly();
            new Thread(() -> {
                String[] splitArticle = article.split(",");
                int id = Integer.parseInt(splitArticle[0]);

                Main.getController().updateCurrentTask("Downloading news article " + splitArticle[0] + ": " + splitArticle[1], false, false);

                String site = null;
                int nullTimeout = 0;

                while (site == null && nullTimeout++ < 10)
                    try {
                        site = downloadArticle(splitArticle[1]);
                    } catch (FileNotFoundException e) {
                        Main.getController().updateCurrentTask("Article is no longer available!", true, false);
                        break;
                    } catch (MalformedURLException e) {
                        Main.getController().updateCurrentTask(e.getMessage(), true, false);
                        if (!Objects.equals(splitArticle[1].substring(0, 3), "http"))
                            splitArticle[1] = "http://" + splitArticle[1];
                    } catch (ConnectException e) {
                        Main.getController().updateCurrentTask("Connection error (Timed Out)", true, false);
                    } catch (Exception e) {
                        Main.getController().updateCurrentTask(e.getMessage(), true, false);
                    }

                try {
                    if (site != null)
                        if (Objects.equals(site, "redirect"))
                            dh.executeCommand("UPDATE newsarticles SET Redirected = 1 WHERE ID = " + id + ";");
                        else
                            dh.executeCommand("UPDATE newsarticles SET Content='" + site + "' WHERE ID = " + id + ";");
                    else
                        dh.executeCommand("UPDATE newsarticles SET Blacklisted = 1 WHERE ID = " + id + ";"); //Blacklist if the document could not be retrieved
                } catch (Exception e) {
                    e.printStackTrace();
                    try {
                        dh.executeCommand("UPDATE newsarticles SET Blacklisted = 1 WHERE ID = " + id + ";"); //Blacklist if the Content causes SQL error (i.e. truncation)
                    } catch (SQLException e1) {
                        e1.printStackTrace();
                    }
                }

                Controller.updateProgress(++progress, t, pb);
                availableThreads.release();
            }).start();
        }

        while (availableThreads.availablePermits() != DOWNLOAD_THREADS) TimeUnit.SECONDS.sleep(1);

        Controller.updateProgress(0, pb);
    }

    /**
     * Scrapes the main content of a news article, given its URL
     * @param url The URL of the news article to download
     * @return The news article content
     * @throws IOException Throws IOException if the request fails due to server unavailability or connection refusal
     */
    private static String downloadArticle(String url) throws IOException {
        URL site = new URL(url);
        HttpURLConnection.setFollowRedirects(true);
        HttpURLConnection conn = (HttpURLConnection) site.openConnection(); //Written by https://stackoverflow.com/questions/15057329/how-to-get-redirected-url-and-content-using-httpurlconnection
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        conn.connect();
        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

        String input;
        StringBuilder html = new StringBuilder();

        while ((input = br.readLine()) != null) html.append(input);

        conn.disconnect();
        br.close();

        StringBuilder strippedHTML = new StringBuilder();

        try {
            Document doc = Jsoup.parse(html.toString());
            Elements p = doc.getElementsByTag("p");

            int i = 0;
            for (Element el : p) {
                strippedHTML.append(el.text());
                if (i++ < p.size()) strippedHTML.append(" ");
            }

            if (Objects.equals(html.toString().toLowerCase(), "redirect")) return "redirect";
        } catch (Exception e) {
            e.printStackTrace();
        }

        String cleanHTML = Jsoup.clean(strippedHTML.toString(), Whitelist.basic()).replaceAll("'", "").trim();

        if (cleanHTML.isEmpty()) return null;

        return cleanHTML;
    }

    /**
     * Downloads news article history for a list of stock tickers
     * @param stockList List of stock tickers to download news for (e.g. AAPL, AAL, BIIB etc.)
     * @throws IOException Throws IOException if the request fails due to server unavailability or connection refusal
     * @throws SQLException Throws SQLException if there is an error with accessing the MySQL/MariaDB database
     * @throws InterruptedException Throws InterruptedException if the sleep function is interrupted by another process
     */
    static void getHistoricNews(ArrayList<String> stockList) throws IOException, SQLException, InterruptedException {
        double t = stockList.size() - 1;
        progress = 0;
        for (String symbol : stockList) {
            getHistoricNews(symbol);
            Controller.updateProgress(++progress, t, pb);
            dh.sendSQLFileToDatabase(true);
        }
    }
}