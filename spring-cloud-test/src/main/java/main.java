import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Pos;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import sun.awt.windows.ThemeReader;
import sun.misc.Timer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static jdk.nashorn.internal.objects.NativeFunction.call;


public class main {
    static String url_redis = "http://10.160.109.63:8080/team2/evaluation/test";
    static String body_redis = "entity=openstack01&startTimestamp=1568296200&endTimestamp=1568296399&algorithm=rf";
    static String url_cloud = "http://localhost:5555/api-powerpredict/powerpredict";
    static String body_cloud = "host=1&start_timestamp=1&end_timestamp=100";
    static class GetTestThread extends Thread{
        @Override
        public void run() {
            System.out.println("===========================================");
            String m = SendGET("http://localhost:5555/api-powerpredict/powerpredict");
            System.out.println(m);
        }
    }

    static class PostWithBodyThread extends Thread{
        @Override
        public void run() {
            System.out.println(PostRequest.RestPost(url_redis));
        }
    }
    static long time = 1568297500;
    static class PostTestThread extends Thread{
        String body = null;
        CyclicBarrier cb = null;
        PostTestThread(String b,CyclicBarrier c){
            cb = c;
            body = b;
        }
        PostTestThread(String b){
            body = b;
        }
        @Override
        public void run() {
            System.out.println("===========================================");
            try {
                long s = System.currentTimeMillis();
                if(cb!=null) {
                    cb.await();
                }
                String m = sendPost(url_cloud,body);
                long e = System.currentTimeMillis();
                System.out.println(m);
                System.out.println(e-s);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws InterruptedException, IOException {
        CyclicBarrier cb = new CyclicBarrier(5);
        long time = 1568510473;
        for (int i=0;i<50;i++){
            new Thread(()->{
                CloseableHttpResponse response = null;
                Random random = new Random();
                long t = random.nextInt(10000) + time;
                try {
                    CloseableHttpClient httpclient = HttpClients.createDefault();
                    HttpGet httpget = new HttpGet("http://10.160.109.63:5555/api-powerpredict/powerpredict/"+t+"/RF/compute01");
                    response = httpclient.execute(httpget);
                    //4.处理结果
                    System.out.println(EntityUtils.toString(response.getEntity()));
                } catch (ClientProtocolException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        response.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                //SendGET("http://localhost:5555/api-powerpredict/powerpredict/"+time+"/ARIMA/compute01");

            }).start();

            Thread.sleep(20);
        }
    }

    public static boolean isNumeric(String str) {
        str = str.trim();
        if(str.length()==0) return false;
        if(str.contains("e")&&str.contains("E")) return false;
        String[] split = null;
        boolean flag_e = false;
        if(str.contains("e")){
            split = str.split("e");
            flag_e = true;
        }else if (str.contains("E")){
            split = str.split("E");
            flag_e = true;
        }
        if(flag_e==true){
            if(split.length!=2){
                return false;
            }
            if(split[0].equals("")) return false;
            for(int i=0;i<split[1].length();i++){
                if(i==0){
                    if((split[1].charAt(i)>'9' || split[1].charAt(i)<'0')){
                        if((split[1].charAt(i)=='+' || split[1].charAt(i)=='-')){
                            if(split[1].length()==1)
                                return false;
                        }else{
                            return false;
                        }
                    }
                }else{
                    if(split[1].charAt(i)>'9' || split[1].charAt(i)<'0')
                        return false;
                }
            }
            boolean flag = false;
            for(int i=0;i<split[0].length();i++){
                if(i==0){
                    if((split[0].charAt(i)>'9' || split[0].charAt(i)<'0')) {
                        if (split[0].charAt(i)!='+' && split[0].charAt(i)!='-'){
                            if(split[0].charAt(i)=='.'){
                                if(split[0].length()==1) return false;
                                flag = true;
                            }else{
                                return false;
                            }
                        }else{
                            if (split[0].length()==1){
                                return false;
                            }
                        }
                    }
                }else{
                    if(split[0].charAt(i)>'9' || split[0].charAt(i)<'0'){
                        if(!flag){
                            if(split[0].charAt(i)=='.'){

                                flag = true;
                            }else{
                                return false;
                            }
                        }else{
                            return false;
                        }
                    }
                }
            }
        }else{
            boolean flag = false;
            for(int i=0;i<str.length();i++){
                if(i==0){
                    if((str.charAt(i)>'9' || str.charAt(i)<'0') &&
                            (str.charAt(i)!='+' && str.charAt(i)!='-'))
                        if(str.charAt(i)=='.'){
                            if(str.length()==1) return false;
                            flag = true;
                        }else{
                            return false;
                        }
                }else{
                    if(str.charAt(i)>'9' || str.charAt(i)<'0'){
                        if(!flag){
                            if(str.charAt(i)=='.'){
                                flag = true;
                                if((i+1<str.length() && (str.charAt(i+1)>'9' || str.charAt(i+1)<'0'))){
                                    return false;
                                }
                                if(i+1>=str.length() && (str.charAt(i-1)>'9' || str.charAt(i-1)<'0')) return false;
                            }else{
                                return false;
                            }
                        }else{
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }
    public static String SendGET(String url) {
        String result="";//访问返回结果
        BufferedReader read=null;//读取访问结果
        try {
            //创建url
            URL realurl=new URL(url);
            //打开连接
            URLConnection connection=realurl.openConnection();

            //建立连接
            connection.connect();
            // 获取所有响应头字段
            Map<String, List<String>> map = connection.getHeaderFields();
            // 遍历所有的响应头字段，获取到cookies等
            for (String key : map.keySet()) {
                System.out.println(key + "--->" + map.get(key));
            }
            // 定义 BufferedReader输入流来读取URL的响应
            read = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(),"UTF-8"));
            String line;//循环读取
            while ((line = read.readLine()) != null) {
                result += line;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally{
            if(read!=null){//关闭流
                try {
                    read.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return result;
    }

    public static String sendPost(String url, String param) {
        PrintWriter out = null;
        BufferedReader in = null;
        String result = "";
        try {
            // 打开链接并且配置，这是指定动作
            URLConnection conn = new URL(url).openConnection();
            conn.setRequestProperty("accept", "*/*");
            conn.setRequestProperty("connection", "Keep-Alive");
            conn.setRequestProperty("user-agent",
                    "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            // 获取URLConnection对象对应的输出流
            out = new PrintWriter(conn.getOutputStream());
            // 发送请求参数
            out.print(param);
            // 输出流的缓冲
            out.flush();

            // 定义BufferedReader输入流来读取post响应之后生成的数据
            in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
            String line = null;
            while ((line = in.readLine()) != null) {
                result += line;
            }
            // 人走带门
            out.close();
            in.close();
        } catch (Exception e) {
            System.out.println("发送 POST 请求出现异常！" + e);
        }
        return result;
    }

    public static String sendPostWithBody(String url) throws JsonProcessingException {
        Map<String,String> headermap = new HashMap<String, String>();
        Map<String,String> contentmap = new HashMap<String, String>();
        ObjectMapper om = new ObjectMapper();
        headermap.put(om.writeValueAsString("accept"), om.writeValueAsString("*/*"));
        headermap.put(om.writeValueAsString("connection"), om.writeValueAsString("Keep-Alive"));
        headermap.put(om.writeValueAsString("user-agent"), om.writeValueAsString("Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)"));
        headermap.put(om.writeValueAsString("Content-Type"),om.writeValueAsString("application/json;charset=utf-8"));

        contentmap.put(om.writeValueAsString("entity"),om.writeValueAsString("openstack01"));
        contentmap.put(om.writeValueAsString("startTimestamp"),om.writeValueAsString("1568294200"));
        contentmap.put(om.writeValueAsString("endTimestamp"),om.writeValueAsString("1568294299"));
        contentmap.put(om.writeValueAsString("algorithm"),om.writeValueAsString("rf"));
        PostRequest pr = new PostRequest();
        String res = pr.postMap(url,headermap,contentmap);
        return res;
    }
}
