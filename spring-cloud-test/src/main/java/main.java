import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Pos;
import sun.awt.windows.ThemeReader;
import sun.misc.Timer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;


public class main {
    static String url_redis = "http://10.160.109.63:8080/team2/evaluation/test";
    static String body_redis = "entity=openstack01&startTimestamp=1568296200&endTimestamp=1568296399&algorithm=rf";
    static String url_cloud = "http://localhost:5555/api-a/powerpredict";
    static String body_cloud = "host=1&start_timestamp=1&end_timestamp=100";
    static class GetTestThread extends Thread{
        @Override
        public void run() {
            System.out.println("===========================================");
            String m = SendGET("http://localhost:5555/api-a/hello/producer");
            System.out.println(m);
        }
    }
    static class PostTestThread extends Thread{
        String body = null;
        CyclicBarrier cb;
        PostTestThread(String b,CyclicBarrier c){
            cb = c;
            body = b;
        }
        PostTestThread(String b){
            body = b;
        }
        @Override
        public void run() {
            //System.out.println("===========================================");
            String m = sendPost(url_cloud,body);
            System.out.println(m);
            long et = System.currentTimeMillis();
            System.out.println(et);
        }
    }
    static class PostWithBodyThread extends Thread{
        @Override
        public void run() {
            System.out.println(PostRequest.RestPost(url_redis));
        }
    }
    static long time = 1568296200;
    public static void main(String[] args) throws InterruptedException {
        long st = System.currentTimeMillis();
        System.out.println(st);
        String m = sendPost(url_cloud,"host=compute01&tagetTimestamp="+time+"&algorithm=rf");
        System.out.println(m);
        long et = System.currentTimeMillis();
        System.out.println(et);
        Thread.sleep(2000);
        time = time + 100;
        System.out.println(System.currentTimeMillis());
        m = sendPost(url_cloud,"host=compute01&tagetTimestamp="+time+"&algorithm=rf");
        System.out.println(m);
        et = System.currentTimeMillis();
        System.out.println(et);
        Thread.sleep(2000);
        time = time+100;
        System.out.println(System.currentTimeMillis());
        m = sendPost(url_cloud,"host=compute01&tagetTimestamp="+time+"&algorithm=rf");
        System.out.println(m);
        et = System.currentTimeMillis();
        System.out.println(et);
        /*
        for (int i=0;i<1;i++){
            time = time+50;
            new PostTestThread("host=compute01&tagetTimestamp="+time+"&algorithm=rf").start();
        }
        for(int i=0;i<500;i++){
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            new GetTestThread().start();
        }
         */
    }
    public static String SendGET(String url) {
        String result="";//访问返回结果
        BufferedReader read=null;//读取访问结果
        try {
            //创建url
            URL realurl=new URL(url);
            //打开连接
            URLConnection connection=realurl.openConnection();
            // 设置通用的请求属性
            connection.setRequestProperty("accept", "*/*");
            connection.setRequestProperty("connection", "Keep-Alive");
            connection.setRequestProperty("user-agent","Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)");

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
            //conn.setRequestProperty("Content-Type","application/json;charset=utf-8");
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
