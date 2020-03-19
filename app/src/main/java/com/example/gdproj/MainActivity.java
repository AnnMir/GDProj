package com.example.gdproj;


import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;

import java.io.BufferedInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private DriveServiceHelper driveServiceHelper;
    static final int PICK_FILE_REQUEST = 1;
    static final int REQUEST_PERMISSION = 2;
    private EditText editText;
    private GoogleAccountCredential credential;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        editText = findViewById(R.id.text);
        checkPermission();
    }

    private void checkPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            requestSignIn();
        } else {
            ActivityCompat.requestPermissions(this, new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_PERMISSION);
        }
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    requestSignIn();
                } else {
                    // в разрешении отказано (в первый раз, когда чекбокс "Больше не спрашивать" ещё не показывается)
                    if (ActivityCompat.shouldShowRequestPermissionRationale(this,
                            Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        finish();
                    }
                    // в разрешении отказано (выбрано "Больше не спрашивать")
                    else {
                        // показываем диалог, сообщающий о важности разрешения
                        AlertDialog.Builder builder = new AlertDialog.Builder(this);
                        builder.setMessage(
                                "Вы отказались предоставлять разрешение на чтение хранилища.\n\nЭто необходимо для работы приложения."
                                        + "\n\n"
                                        + "Нажмите \"Предоставить\", чтобы предоставить приложению разрешения.")
                                // при согласии откроется окно настроек, в котором пользователю нужно будет вручную предоставить разрешения
                                .setPositiveButton("Предоставить", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        finish();
                                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.fromParts("package", getPackageName(), null));
                                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                        startActivity(intent);
                                    }
                                })
                                // закрываем приложение
                                .setNegativeButton("Отказаться", new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int id) {
                                        finish();
                                    }
                                });
                        builder.setCancelable(false);
                        builder.create().show();
                    }
                }
                break;
            }
        }
    }

    private void requestSignIn() {
        GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                .build();

        GoogleSignInClient client = GoogleSignIn.getClient(this, signInOptions);

        startActivityForResult(client.getSignInIntent(),400);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case 400:
                if (resultCode == RESULT_OK) {
                    handleSignInIntent(data);
                }
                break;
            case PICK_FILE_REQUEST:
                if(data != null){
                    Uri selectedRes = data.getData();
                    editText.setText(selectedRes.toString());
                }else{
                    Toast.makeText(this.getApplicationContext(), "Nothing was selected", Toast.LENGTH_SHORT).show();
                }
                break;
        }
    }

    private void handleSignInIntent(Intent data) {
        GoogleSignIn.getSignedInAccountFromIntent(data)
                .addOnSuccessListener(googleSignInAccount -> {
                    credential = GoogleAccountCredential
                            .usingOAuth2(MainActivity.this, Collections.singleton(DriveScopes.DRIVE_FILE));
                    credential.setSelectedAccount(googleSignInAccount.getAccount());
                    credential.setBackOff(new ExponentialBackOff());

                    Drive googleDriveService = new Drive.Builder(
                            AndroidHttp.newCompatibleTransport(),
                            new GsonFactory(),
                            credential)
                            .setApplicationName("GDProj")
                            .build();

                    driveServiceHelper = new DriveServiceHelper(googleDriveService);

                })
                .addOnFailureListener(e -> {

                });
    }

    public void chooseFile(View v){
        editText.setText("");
        Intent intent = new Intent();
        //sets the select file to all types of files
        intent.setType("*/*");
        //allows to select data and return it
        intent.setAction(Intent.ACTION_PICK);
        //starts new activity to select file and return data
        startActivityForResult(Intent.createChooser(intent,"Choose File to Upload.."),PICK_FILE_REQUEST);
    }

    public void addVideo(View view) {
        editText.setText("");
        Intent intent = new Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI);
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }


    public void uploadFile(View v){
        ProgressDialog progressDialog = new ProgressDialog(MainActivity.this);
        progressDialog.setTitle("Uploading to Google Drive");
        progressDialog.setMessage("Please wait...");
        progressDialog.show();

        String filePath = "";
            Uri uri = Uri.parse(editText.getText().toString());
            filePath = getFilePath(uri);
            File file = new File(filePath);
            Toast.makeText(getApplicationContext(),filePath,Toast.LENGTH_LONG).show();

        driveServiceHelper.createFile(file.getName(), filePath)
                .addOnSuccessListener(s -> {
                    progressDialog.dismiss();
                    Toast.makeText(getApplicationContext(), "Uploaded successfully", Toast.LENGTH_LONG).show();
        })
                .addOnFailureListener(e -> {
                    progressDialog.dismiss();
                    Toast.makeText(getApplicationContext(), "Check your google drive api key", Toast.LENGTH_LONG).show();
                });
    }

    public void resumableUpload(View v){
        String filePath = "";
        Uri uri = Uri.parse(editText.getText().toString());
        filePath = getFilePath(uri);
        File file = new File(filePath);
        new ResumableLoad(credential, file, getMimeType(uri)).execute();
    }

    // метод возвращает полный реальный путь до файла, включая имя и расширение
    public  String getFilePath(Uri uri) {
        String selection = null;
        String[] selectionArgs = null;
        if (Build.VERSION.SDK_INT >= 19 && DocumentsContract.isDocumentUri(this, uri)) {
            if (isExternalStorageDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                return Environment.getExternalStorageDirectory() + "/" + split[1];
            } else if (isDownloadsDocument(uri)) {
                final String id = DocumentsContract.getDocumentId(uri);
                uri = ContentUris.withAppendedId( Uri.parse("content://downloads/public_downloads"), Long.valueOf(id));
            } else if (isMediaDocument(uri)) {
                final String docId = DocumentsContract.getDocumentId(uri);
                final String[] split = docId.split(":");
                final String type = split[0];
                if ("image".equals(type)) {
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                } else if ("video".equals(type)) {
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if ("audio".equals(type)) {
                    uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                }
                selection = "_id=?";
                selectionArgs = new String[]{split[1]};
            }
        }
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            String[] projection = {MediaStore.Images.Media.DATA};
            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri, projection, selection, selectionArgs, null);
                int column_index = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA);
                if (cursor.moveToFirst()) {
                    return cursor.getString(column_index);
                }
            } catch (Exception e) {
            } finally { if (cursor != null)   cursor.close();
            }
        } else if ("file".equalsIgnoreCase(uri.getScheme())) {
            return uri.getPath();
        }
        return null;
    }

    public static boolean isExternalStorageDocument(Uri uri) {
        return "com.android.externalstorage.documents".equals(uri.getAuthority());
    }

    public static boolean isDownloadsDocument(Uri uri) {
        return "com.android.providers.downloads.documents".equals(uri.getAuthority());
    }

    public static boolean isMediaDocument(Uri uri) {
        return "com.android.providers.media.documents".equals(uri.getAuthority());
    }

    // метод возвращает из полного пути расширение файла
    public String getFileExtension(String path) {
        int pos = path.lastIndexOf(".");
        if (pos != -1) return path.substring(pos + 1);
        else return "";
    }

    public String getMimeType(Uri uri) {
        String mimeType;
        if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
            ContentResolver cr = this.getContentResolver();
            mimeType = cr.getType(uri);
        } else {
            String fileExtension = MimeTypeMap.getFileExtensionFromUrl(uri
                    .toString());
            mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                    fileExtension.toLowerCase());
        }
        return mimeType;
    }
}

class ResumableLoad extends AsyncTask<URL, String, String> {

    GoogleAccountCredential credential;
    Uri uri;
    File file;
    String mimeType;
    String sessionUri;

    ResumableLoad(GoogleAccountCredential cred, File _file, String _mimeType) {
        credential = cred;
        file = _file;
        mimeType = _mimeType;
    }

    @Override
    protected String doInBackground(URL... urls) {
        HttpURLConnection request = null;
        HttpURLConnection _request = null;
        HttpURLConnection __request = null;
        try {
            //String request = "POST /upload/drive/v3/files?uploadType=resumable HTTP/1.1 Host: www.googleapis.com Authorization: Bearer your_auth_token Content-Length: 38 Content-Type: application/json; charset=UTF-8 X-Upload-Content-Type: image/jpeg X-Upload-Content-Length: 2000000 { \"name\": \"My File\" }";
            URL url = new URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable");
            request = (HttpURLConnection) url.openConnection();
            request.setRequestMethod("POST");
            request.setDoInput(true);
            request.setDoOutput(true);
            request.setRequestProperty("Authorization", "Bearer " + credential.getToken());
            request.setRequestProperty("X-Upload-Content-Type", mimeType);
            request.setRequestProperty("X-Upload-Content-Length", String.format(Locale.ENGLISH, "%d", file.length()));
            Log.i("GDProj", String.format(Locale.ENGLISH, "%d", file.length()));
            request.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            String body = "{\"name\": \"" + file.getName() + "\"}";
            request.setRequestProperty("Content-Length", String.format(Locale.ENGLISH, "%d", body.getBytes().length));
            OutputStream outputStream = request.getOutputStream();
            outputStream.write(body.getBytes());

            outputStream.flush();
            outputStream.close();
            request.connect();
            if (request.getResponseCode() == HttpURLConnection.HTTP_OK) {
                sessionUri = request.getHeaderField("location");
                Log.i("GDProj", sessionUri);
            }


            //simple resumable upload
            URL url1 = new URL(sessionUri);
            request = (HttpURLConnection) url1.openConnection();
            request.setRequestMethod("PUT");
            request.setDoOutput(true);
            String len = Long.toString(file.length());
            Log.i("GDProj", "length:" + len);
            //request.setRequestProperty("Content-Length", len);
            request.setRequestProperty("Content-Type", mimeType);

            DataOutputStream output = new DataOutputStream(request.getOutputStream());
            //output.writeUTF("[");
            //String body = "{\"name\": \"" + file.getName() + "\"}";
            //output.writeUTF("{\"file\": \"");
            FileInputStream inputFile = new FileInputStream(file);
            byte[] buffer = new byte[64 * 1024];
            int counter;
            while ((counter = inputFile.read(buffer)) != -1) {
                output.write(buffer, 0, counter);
            }

            //output.writeUTF("\"}");
            output.flush();
            request.connect();
            int response = request.getResponseCode();
            String code = "" + response;
            Log.i("GDProg", code);
            if (response == HttpURLConnection.HTTP_OK) {
                Log.i("GDProj", "Success send");
            }else{
                String s = response + " " + request.getResponseMessage();
                Log.e("GDProj", s);
            }
        } catch (IOException | GoogleAuthException e) {
            e.printStackTrace();
        } finally {
            if (request != null)
                request.disconnect();
            if (_request != null)
                _request.disconnect();
            if (__request != null)
                __request.disconnect();
        }
        return null;
    }

    @Override
    protected void onPostExecute(String s) {
        super.onPostExecute(s);
    }

    byte[] FileToBytes(File file) {
        int size = (int) file.length();
        byte[] bytes = new byte[size];
        try {
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
            buf.read(bytes, 0, bytes.length);
            buf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return bytes;
    }
}