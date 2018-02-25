import javafx.scene.control.ProgressBar;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Whitelist;
import org.jsoup.select.Elements;

import java.io.*;
import java.net.*;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

class NewsAPIHandler {
    private static final String INTRINIO_API_CALL = "https://api.intrinio.com/news?ticker=";
    private static final String INTRINIO_CSV_CALL = "https://api.intrinio.com/news.csv?page_size=10000&ticker=";
    static private String INTRINIO_USERNAME;
    static private String INTRINIO_PASSWORD;
    static private final int PAGES = 0, ARTICLES = 1, PAGE_SIZE = 10000; //Indices for accessing JSON metadata
    static private DatabaseHandler dh;
    static private ProgressBar pb;
    static private double progress = 0;

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

    private static void getHistoricNews(String stock) throws IOException, SQLException, InterruptedException {
        if (isOverLimit(0)) return;

        int values[] = getCSVMetaData(stock);

        int storedArticles = Integer.parseInt(dh.executeQuery("SELECT COUNT(*) FROM newsarticles WHERE Symbol='" + stock + "';").get(0));


        int missingArticles = values[ARTICLES] - storedArticles;

        System.out.println("MISSING ARTICLES FOR '" + stock + "': " + missingArticles);

        if (missingArticles == 0)
            return;

        if (missingArticles < 0) {
            System.err.println("NEGATIVE MISSING ARTICLE VALUE - May be due to API inaccessibility");
            return;
        }

        int i = 1;

        while (i <= values[PAGES] && missingArticles > 0) {
            missingArticles -= getCSVNews(stock, i++, missingArticles);
        }

        if (missingArticles > 0)
            System.err.println("DID NOT DOWNLOAD ALL ARTICLES");
    }

    static public void initialise(DatabaseHandler nddh, ProgressBar pb) {
        dh = nddh;
        NewsAPIHandler.pb = pb;

        System.out.println("Initialised News API Handler");
    }

    private static int getCurrentCalls() throws SQLException {
        ArrayList<String> sCalls = dh.executeQuery("SELECT Calls FROM apicalls WHERE Date = CURDATE() AND Name='INTRINIO';");

        if(!sCalls.isEmpty())
            return Integer.parseInt(sCalls.get(0));

        return 0;
    }

    private static boolean isOverLimit(int callsToPerform) throws SQLException {
        int limit = Integer.parseInt(dh.executeQuery("SELECT DailyLimit FROM apimanagement WHERE Name='INTRINIO';").get(0));

        return (callsToPerform + getCurrentCalls()) > limit;
    }

    private static int[] getCSVMetaData(String stock) throws IOException, SQLException, InterruptedException {
        URL url = new URL(INTRINIO_CSV_CALL + stock);

        TimeUnit.MILLISECONDS.sleep(1000); // To prevent blocking

        URLConnection connect = url.openConnection();
        InputStreamReader isr = null;

        try {
            isr = new InputStreamReader(url.openStream());
        } catch (IOException e) {
            HttpURLConnection http = (HttpURLConnection) connect;
            if (http.getResponseCode() == 429)
                System.err.println("Too many requests"); //TODO: Make a GUI graphic that shows this has occurred

            ((HttpURLConnection) connect).disconnect();

            dh.executeCommand("INSERT INTO apicalls VALUES ('INTRINIO', CURDATE(), 500) ON DUPLICATE KEY UPDATE Calls = 500;"); //Incase another system uses this program, this database value doesn't get updated, in which case if an error occurs, mark the api as "limit reached"
        }

        if (isr == null)
            return new int[]{0, 0};

        BufferedReader br = new BufferedReader(isr);

        String curr;

        curr=br.readLine();

        String[] splitString = curr.split(",");

        dh.executeCommand("INSERT INTO apicalls VALUES('INTRINIO', CURDATE(), 1) ON DUPLICATE KEY UPDATE Calls = Calls +1;");

        int pages = Integer.parseInt(splitString[3].split(":")[1].trim());
        int articles = Integer.parseInt(splitString[0].split(":")[1].trim());

        return new int[]{pages,articles};
    }

    private static int getCSVNews(String stock, int page, int missingArticles) throws IOException, SQLException, InterruptedException {
        System.out.println("Getting headlines for " + stock + " (Page " + page + ")");
        URL url = new URL(INTRINIO_CSV_CALL + stock + "&page_number=" + page);

        TimeUnit.MILLISECONDS.sleep(1000);
        URLConnection connect = url.openConnection();
        InputStreamReader isr = null;

        try {
            isr = new InputStreamReader(url.openStream());
        } catch (IOException e) {
            HttpURLConnection http = (HttpURLConnection) connect;
            if (http.getResponseCode() == 429)
                System.err.println("Too many requests"); //TODO: Make a GUI graphic that shows this has occurred

            ((HttpURLConnection) connect).disconnect();

            dh.executeCommand("INSERT INTO apicalls VALUES ('INTRINIO', CURDATE(), 500) ON DUPLICATE KEY UPDATE Calls = 500;"); //Incase another system uses this program, this database value doesn't get updated, in which case if an error occurs, mark the api as "limit reached"
        }

        if (isr == null) {
            System.out.println("Could not connect URL Stream");
            return -1;
        }

        BufferedReader br = new BufferedReader(isr);
        String curr;

        ArrayList<String> newsArray = new ArrayList<>();

        System.out.println("Downloading & Reading '" + stock + "' PAGE " + page + " news file...");

        for (int i = 0; i < 2; i++)  //Remove preamble
            br.readLine();

        while((curr = br.readLine())!=null)
            newsArray.add(curr.replace("'", "").replace("`", "").replace("\"", ""));

        br.close();

        System.out.println("Sorting '" + stock + "' PAGE " + page + " news file into chronological order...");

        //Preprocess news data to remove corrupted entries
        Set<String> newsSet = new LinkedHashSet<>(); //Linked hashset retains insertion order and removes duplicates

        System.out.println("Cleaning '" + stock + "' PAGE " + page + " news file...");

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

                while (i < splitString.length && !(splitString[i].matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}\\s.\\d{4}") || splitString[i].matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}"))) {
                    headline.append(splitString[i++].replace(",", ""));
                }

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
                    System.err.println("NO DATE FOUND IN: " + news);
                newsSet.add(news);
            }
        }

        newsArray.clear();

        int downloaded = 0;
        int newsSize = newsSet.size();

        System.out.println("'" + stock + "' PAGE " + page + " WITH " + newsSize + " (Missing " + missingArticles + " articles)");

        dh.setAutoCommit(false);

        for (String news : newsSet) {
            String[] splitNews = news.split(",");
            String title = splitNews[3];
            String summary = splitNews[6];
            String date = splitNews[4];
            String link = splitNews[5];
            date = date.split(" ")[0] + " " + date.split(" ")[1];

            if (!date.matches("\\d{4}-\\d{2}-\\d{2}\\s\\d{2}:\\d{2}:\\d{2}"))
                System.err.println("NO DATE FOUND IN: " + news);

            String data = "'" + stock + "','" + title + "','" + summary + "','" + date + "','" + link + "'";

            String query = "SELECT 1 FROM newsarticles WHERE Symbol='" + stock + "' AND Headline='" + title + "' AND Published='" + date + "' AND URL ='" + link + "';";
            ArrayList<String> result = dh.executeQuery(query);

            if (result.isEmpty()) {
                System.out.println("Discovered News Article for " + stock + ": " + title);
                String command;

                command = "INSERT INTO newsarticles (Symbol, Headline,Description,Published,URL,Duplicate) VALUES (" + data + ", (SELECT COALESCE((SELECT * FROM (SELECT 1 FROM newsarticles WHERE Symbol='" + stock + "' AND (Headline='" + title + "' OR URL='" + link + "') LIMIT 1) as t),0)));";


                dh.addBatchCommand(command);

                missingArticles--;
                downloaded++;

                if (missingArticles == 0)
                    return downloaded;
            }
        }
        dh.executeBatch();
        dh.setAutoCommit(true);

        return downloaded;
    }

    public static void downloadArticles() throws SQLException {
        System.out.println("Downloading missing news article content...");
        Controller.updateProgress(ProgressBar.INDETERMINATE_PROGRESS, pb);
        ArrayList<String> undownloadedArticles = dh.executeQuery("SELECT ID, URL FROM newsarticles WHERE Content IS NULL AND Blacklisted = 0 AND Redirected = 0 AND Duplicate = 0 AND URL != \"\";");

        if (undownloadedArticles == null || undownloadedArticles.isEmpty()) {
            Controller.updateProgress(0, pb);
            return;
        }

        double t = undownloadedArticles.size() - 1;
        progress = 0;

        for (String article : undownloadedArticles) {
                String[] splitArticle = article.split(",");
                int id = Integer.parseInt(splitArticle[0]);

                System.out.println("Downloading news article " + splitArticle[0] + ": " + splitArticle[1]);

                String site = null;


            while (site == null)
                try {
                    site = NewsAPIHandler.downloadArticle(splitArticle[1]);
                } catch (FileNotFoundException e) {
                    System.err.println("Article is no longer available!");
                    break;
                } catch (MalformedURLException e) {
                    System.err.println(e.getMessage());
                    if (!Objects.equals(splitArticle[1].substring(0, 3), "http"))
                        splitArticle[1] = "http://" + splitArticle[1];
                } catch (ConnectException e) {
                    System.err.println("Connection error (Timed Out)");
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
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
                    } catch (SQLException e1) { e1.printStackTrace(); }
                }

                Controller.updateProgress(++progress, t, pb);
        }


        Controller.updateProgress(0, pb);
    }

    private static String downloadArticle(String url) throws IOException {
        URL site = new URL(url);
        HttpURLConnection.setFollowRedirects(true);
        HttpURLConnection conn = (HttpURLConnection) site.openConnection(); //Written by https://stackoverflow.com/questions/15057329/how-to-get-redirected-url-and-content-using-httpurlconnection
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.11 (KHTML, like Gecko) Chrome/23.0.1271.95 Safari/537.11");

        conn.connect();

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));

        String input;
        StringBuilder html = new StringBuilder();

        while ((input = br.readLine()) != null)
            html.append(input);

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

            if (Objects.equals(html.toString().toLowerCase(), "redirect"))
                return "redirect";
        } catch (Exception e) {
            e.printStackTrace();
        }

        String cleanHTML = Jsoup.clean(strippedHTML.toString(), Whitelist.basic()).replaceAll("'", "").trim();

        if (cleanHTML.isEmpty())
            return null;

        return cleanHTML;
    }

    static public void getNews(String stock, int page) throws IOException, SQLException {

        URL url = new URL(INTRINIO_API_CALL + stock + "&page_number=" + page);

        if(page == 1)
            System.out.println("Downloading Latest News for " + stock + "...");
        else
            System.out.println("Downloading Historical News (Page " + page + ") for " + stock + "...");

        String doc;
        try (InputStream in = url.openStream()) {
            Scanner s = new Scanner(in).useDelimiter(("\\A"));
            doc = s.next();
        }

        dh.executeCommand("INSERT INTO apicalls VALUES('INTRINIO', CURDATE(), 1) ON DUPLICATE KEY UPDATE Calls = Calls +1;");

        try {
            JSONObject obj = new JSONObject(doc);
            JSONArray arr = obj.getJSONArray("data");
            String punctuationRemover = "'";

            for (int i = 0; i < arr.length(); i++) {
                JSONObject ob2 = (JSONObject) arr.get(i);
                String title = ob2.getString("title").replaceAll(punctuationRemover, "");
                String summary = ob2.getString("summary").replaceAll(punctuationRemover, "");
                String date = ob2.getString("publication_date").replaceAll(punctuationRemover, "");
                String link = ob2.getString("url").replaceAll(punctuationRemover, "");
                date = date.split(" ")[0] + " " + date.split(" ")[1];

                String data = "'" + stock + "','" + title + "','" + summary + "','" + date + "','" + link + "'";

                String query = "SELECT * FROM newsarticles WHERE headline = '" + title + "' AND Symbol = '" + stock + "'";

                ArrayList<String> results = dh.executeQuery(query);

                if(results.isEmpty()) {
                    System.out.println(title);
                    String command = "INSERT INTO newsarticles (Symbol, Headline,Description,Published,URL) VALUES (" + data + ");";

                    try {
                        dh.executeCommand(command);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    static public void getHistoricNews(ArrayList<String> stockList) throws IOException, SQLException, InterruptedException {
        double t = stockList.size() - 1;
        progress = 0;
        for (String symbol : stockList) {
            getHistoricNews(symbol);
            Controller.updateProgress(++progress, t, pb);
            dh.sendSQLFileToDatabase(false);
        }
    }
}