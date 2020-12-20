package com.xk.quick2smali;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 功能:`
 * 一行命令，反编译apk/jar/dex为smali，并用vscode打开。支持根据时间戳缓存文件，第二次秒开。
 * eg: 2s demo-release.apk
 * 准备工作：
 * 0.保证java可用
 * 1.安装vscode，并按下面配置code命令
 * 1.打开vscode，command + shift + p 打开命令面板（或者点击菜单栏 查看>命令面板）
 * 2.输入shell（选择"install code command in PATH"）
 * 4.保证dx命令可用。具体百度
 * 5.把该文件放到任意目录，配置一个别名，比如我配了2s(to Smali的简写)。只需要在控制台输入 2s xxx.apk/dex/jar即可反编译apk、dex、jar，并用vs打开
 * mac配置别名方式：
 * 1.打开终端， open ~/.bash_profile。
 * 2.配置alias 2s='java -jar xxx.jar'
 * 3.保存，打开控制台，输入2s 回车。测试
 */
public class Main {
    static String outputRoot = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath()).getParent() + "/quick2SmaliTemp";
    static String bakSmaliJar;
    static String AXMLPriter;
    static String aapt2;
    static String fernflower;
    int threadCount = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) {
        Main quick2Smali = new Main();
        bakSmaliJar = FileUtils.getResource("baksmali.jar").getAbsolutePath();
        aapt2 = FileUtils.getResource("aapt2").getAbsolutePath();
        fernflower = FileUtils.getResource("fernflower.jar").getAbsolutePath();
        AXMLPriter = FileUtils.getResource("AXMLPrinter.jar").getAbsolutePath();
        quick2Smali.exec(new File(args[0]));
    }

    private void exec(File file) {

        System.out.println(getOutputFileName(file));
        File cache = getCache(file);
        if (cache != null) {
            System.out.println("有缓冲，直接打开vscode。" + cache);
            execCmd("code " + cache);
            return;
        }
        if (file.isDirectory()) {
            decodeDir(file);
        } else if (file.getName().endsWith(".apk")) {
            apk2Smali(file);
        } else if (file.getName().endsWith(".dex")) {
            dex2Smali(file);
        } else if (file.getName().endsWith(".jar")) {
//            jar2Smali(file);
            jar2Java(file);
        } else {
            System.out.println("不支持的类型");
        }
        System.out.println("ok！");
    }

    private void jar2Java(File file) {
        System.out.println("准备jar2java");
        System.out.println(getOutputFilePath(getOutputFileName(file)).mkdirs());
        String jar2JavaCmd = String.format("java -jar %s -dgs=true %s %s", fernflower, file, getOutputFilePath(getOutputFileName(file)));
        String unzip = String.format("unzip -o %s/%s -d %s", getOutputFilePath(getOutputFileName(file)), file.getName(), getOutputFilePath(getOutputFileName(file)));
        System.out.println(unzip);
        if (execCmd(jar2JavaCmd) && execCmd(unzip)) {
            openAndSaveCache(file);
            System.out.println("jar2JavaCmd成功");
        } else {
            System.out.println("jar2JavaCmd失败");
        }
    }

    private void decodeDir(File file) {

    }

    private void openAndSaveCache(File file) {
        execCmd("code " + getOutputFilePath(file));
        putCache(file);
    }

    private void putCache(File file) {
        FileWriter fileWriter = null;
        try {
            fileWriter = new FileWriter(outputRoot + "/cache.log", true);
            String outputFileName = getOutputFileName(file);
            fileWriter.append(outputFileName).append("\n");
            System.out.println("写入缓存：" + outputFileName);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (fileWriter != null) {
                try {
                    fileWriter.flush();
                    fileWriter.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private boolean outputManifest1(File file) {
        ZipFile zipFile = null;
        FileOutputStream fileOutputStream = null;
        InputStream inputStream = null;
        try {
            zipFile = new ZipFile(file);
            ZipEntry manifest = zipFile.getEntry("AndroidManifest.xml");
            inputStream = zipFile.getInputStream(manifest);
            File manifestFile = new File(getOutputFilePath(file), "AndroidManifest.xml");
            Files.copy(
                    inputStream,
                    manifestFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            String command = String.format("java -jar %s %s/AndroidManifest.xml > %s/AndroidManifest1.xml", AXMLPriter, getOutputFilePath(file), getOutputFilePath(file));
            if (execCmd(command)) {
                new File(getOutputFilePath(file), "AndroidManifest.xml").delete();
                new File(getOutputFilePath(file), "AndroidManifest1.xml").renameTo(new File(getOutputFilePath(file), "AndroidManifest.xml"));
                return true;
            } else {
                return false;
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (fileOutputStream != null) {
                try {
                    fileOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }

    private boolean outputManifest(File file) {
        String command = String.format("%s dump %s --file AndroidManifest.xml > %s", aapt2, file, getOutputFilePath(file) + "/AndroidManifest_dump.xml");
        System.out.println(command);
        return execCmd(command);
    }

    private void apk2Smali(final File file) {

        try {
            Enumeration<? extends ZipEntry> entries = new ZipFile(file).entries();

            List<String> dexList = new ArrayList<>();
            while (entries.hasMoreElements()) {
                String entryName = entries.nextElement().getName();
                if (entryName.startsWith("classes") && entryName.endsWith(".dex") && !entryName.contains("/")) {
                    dexList.add(entryName);
                }
            }
            System.out.println("准备apk2Smali，apk包含以下dex:" + Arrays.toString(dexList.toArray()));
            final long start = System.currentTimeMillis();
            ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
            final CountDownLatch countDownLatch = new CountDownLatch(dexList.size());
            final boolean[] result = {true};
            for (final String dex : dexList) {
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        String command = String.format("java -jar %s d %s -o %s", bakSmaliJar, file + "/" + dex, getOutputFilePath(file));
                        System.out.println(dex + "开始");
                        if (execCmd(command)) {
                            System.out.println("dex2Smali " + dex + " 成功" + (System.currentTimeMillis() - start));
                        } else {
                            System.out.println("dex2Smali " + dex + " 失败");
                            result[0] = false;
                        }
                        countDownLatch.countDown();
                    }
                });
            }
            countDownLatch.await();
            if (result[0]) {
                if (outputManifest(file)) {
                    openAndSaveCache(file);
                    System.out.println("dex2Smali成功");
                }
            } else {
                System.out.println("dex2Smali失败");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.exit(0);
    }

    static int count = 0;


    private void dex2Smali(File file) {
        System.out.println("准备dex2Smali");
        String dex2SmaliCommand = String.format("java -jar %s d %s -o %s", bakSmaliJar, file, getOutputFilePath(file));
        if (execCmd(dex2SmaliCommand)) {
            System.out.println("dex2Smali成功");
            openAndSaveCache(file);
        } else {
            System.out.println("dex2Smali失败");
        }
    }

    private void jar2Smali(File file) {
        System.out.println("准备jar2dex");
        String jar2DexCmd = String.format("dx --dex --output=%s %s", file + ".dex", file);
        if (execCmd(jar2DexCmd)) {
            System.out.println("jar2dex成功");
            System.out.println("准备dex2Smali");
            String dex2SmaliCommand = String.format("java -jar %s d %s -o %s", bakSmaliJar, file + ".dex", getOutputFilePath(getOutputFileName(file)));
            if (execCmd(dex2SmaliCommand)) {
                System.out.println("dex2Smali成功，准备删除临时文件:" + file + ".dex");
                openAndSaveCache(file);
            } else {
                System.out.println("dex2Smali失败，准备删除临时文件:" + file + ".dex");
            }
            new File(file + ".dex").delete();
        } else {
            System.out.println("jar2Smali失败");
        }
    }

    private boolean execCmd(String command) {
        ProcessBuilder pb;
        Process process = null;
        BufferedReader br = null;
        StringBuilder resMsg;
        OutputStream os = null;
        try {
            pb = new ProcessBuilder("sh");
            pb.redirectErrorStream(true);
            process = pb.start();
            os = process.getOutputStream();
            os.write(command.getBytes());
            os.flush();
            os.close();

            resMsg = new StringBuilder();
            // get command result
            br = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String s;
            while ((s = br.readLine()) != null) {
                resMsg.append(s).append("\n");
            }
            process.waitFor();
            System.out.println(resMsg);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                if (br != null) {
                    br.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (process != null) {
                process.destroy();
            }
        }
        return true;
    }

    SimpleDateFormat format = new SimpleDateFormat("_MM-dd_HH-mm-ss");


    /**
     * 传入一个apk/jar/dex等文件名，会把该文件的文件名拼接时间戳，去cache.log中查，如果有，直接返回，否则返回null
     */
    private File getCache(File file) {
        String simpleOutputFileName = getOutputFileName(file);
        BufferedReader bufferedReader = null;
        try {
            File cacheFile = new File(outputRoot, "cache.log");
            if (!cacheFile.exists()) {
                return null;
            }
            bufferedReader = new BufferedReader(new InputStreamReader(new FileInputStream(cacheFile)));
            String line = null;
            while ((line = bufferedReader.readLine()) != null) {
                if (simpleOutputFileName.equals(line)) {
                    return getOutputFilePath(simpleOutputFileName);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (bufferedReader != null) {
                try {
                    bufferedReader.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return null;
    }

    /**
     * 获取文件的输出目录名
     */
    private String getOutputFileName(File file) {
        return file.getName() + format.format(file.lastModified());
    }

    /**
     * 获取文件的输出全路径
     */
    private File getOutputFilePath(String outputFileName) {
        return new File(outputRoot, outputFileName);
    }

    /**
     * 获取文件的输出全路径
     */
    private File getOutputFilePath(File file) {
        return new File(outputRoot, getOutputFileName(file));
    }
}