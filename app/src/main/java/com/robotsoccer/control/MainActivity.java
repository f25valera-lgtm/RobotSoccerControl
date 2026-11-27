package com.robotsoccer.control;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
public class MainActivity extends Activity {

    // Comandos: deben coincidir con el switch del ESP32
    private static final char ADELANTE = 'a';
    private static final char IZQUIERDA = 'b';
    private static final char DETENER = 'c';
    private static final char DERECHA = 'd';
    private static final char ATRAS = 'e';

    // UUID estándar del perfil de puerto serie (SPP)
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int PERMISO_BT = 1;

    // Un solo hilo para escribir, así los comandos salen en orden
    private final ExecutorService envios = Executors.newSingleThreadExecutor();
    // Botones presionados; manda el más reciente
    private final List<Character> presionados = new ArrayList<>();

    private volatile BluetoothSocket socket;
    private TextView estado;
    private Button botonConectar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout raiz = new LinearLayout(this);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setBackgroundColor(Color.rgb(18, 18, 18));
        raiz.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout barra = new LinearLayout(this);
        barra.setGravity(Gravity.CENTER_VERTICAL);
        estado = new TextView(this);
        estado.setTextSize(18);
        barra.addView(estado, new LinearLayout.LayoutParams(0, -2, 1));
        botonConectar = new Button(this);
        botonConectar.setOnClickListener(v -> {
            if (socket == null) {
                elegirRobot();
            } else {
                desconectar("Desconectado");
            }
        });
        barra.addView(botonConectar);
        raiz.addView(barra, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout controles = new LinearLayout(this);

        // Pulgar izquierdo: avanzar y retroceder
        LinearLayout vertical = new LinearLayout(this);
        vertical.setOrientation(LinearLayout.VERTICAL);
        vertical.addView(crearBoton("▲\nADELANTE", ADELANTE), peso(-1, 0, 1));
        vertical.addView(crearBoton("▼\nATRÁS", ATRAS), peso(-1, 0, 1));
        controles.addView(vertical, peso(0, -1, 2));

        controles.addView(crearBoton("■\nALTO", DETENER), peso(0, -1, 1));

        // Pulgar derecho: giros
        controles.addView(crearBoton("◀\nIZQ", IZQUIERDA), peso(0, -1, 1.5f));
        controles.addView(crearBoton("▶\nDER", DERECHA), peso(0, -1, 1.5f));

        raiz.addView(controles, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(raiz);
        mostrarEstado("Desconectado", false);
    }

    @SuppressLint("ClickableViewAccessibility")
    private Button crearBoton(String texto, char comando) {
        Button b = new Button(this);
        b.setText(texto);
        b.setTextSize(22);
        b.setTextColor(Color.WHITE);
        final int color = comando == DETENER ? Color.rgb(198, 40, 40) : Color.rgb(21, 101, 192);
        b.setBackgroundColor(color);
        b.setOnTouchListener((View v, MotionEvent e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setAlpha(0.6f);
                    if (comando == DETENER) {
                        presionados.clear();
                    } else {
                        presionados.remove(Character.valueOf(comando));
                        presionados.add(comando);
                    }
                    enviarActual();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    presionados.remove(Character.valueOf(comando));
                    enviarActual();
                    return true;
                default:
                    return true;
            }
        });
        return b;
    }

    private void enviarActual() {
        enviar(presionados.isEmpty() ? DETENER : presionados.get(presionados.size() - 1));
    }

    // Se envía un solo carácter, sin salto de línea
    private void enviar(char comando) {
        final BluetoothSocket s = socket;
        if (s == null) return;
        envios.execute(() -> {
            try {
                s.getOutputStream().write(comando);
                s.getOutputStream().flush();
            } catch (IOException ex) {
                perderConexion(s);
            }
        });
    }

    private void elegirRobot() {
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, PERMISO_BT);
            return;
        }

        BluetoothAdapter adaptador = BluetoothAdapter.getDefaultAdapter();
        if (adaptador == null) {
            aviso("Este teléfono no tiene Bluetooth");
            return;
        }
        if (!adaptador.isEnabled()) {
            aviso("Enciende el Bluetooth y vuelve a tocar Conectar");
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }

        final List<BluetoothDevice> equipos = new ArrayList<>(adaptador.getBondedDevices());
        String[] nombres = new String[equipos.size()];
        for (int i = 0; i < nombres.length; i++) {
            nombres[i] = equipos.get(i).getName() + "\n" + equipos.get(i).getAddress();
        }

        AlertDialog.Builder dialogo = new AlertDialog.Builder(this)
                .setTitle(nombres.length == 0 ? "No hay equipos vinculados" : "Elige tu robot")
                .setNeutralButton("Vincular nuevo", (d, w) ->
                        startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("Cancelar", null);
        if (nombres.length > 0) {
            dialogo.setItems(nombres, (d, i) -> conectar(equipos.get(i)));
        }
        dialogo.show();
    }

    @Override
    public void onRequestPermissionsResult(int codigo, String[] permisos, int[] resultados) {
        if (codigo != PERMISO_BT) return;
        if (resultados.length > 0 && resultados[0] == PackageManager.PERMISSION_GRANTED) {
            elegirRobot();
        } else {
            aviso("Activa el permiso de dispositivos cercanos en los ajustes de la app");
        }
    }

    private void conectar(BluetoothDevice equipo) {
        mostrarEstado("Conectando...", false);
        botonConectar.setEnabled(false);
        new Thread(() -> {
            BluetoothSocket nuevo = null;
            try {
                nuevo = equipo.createRfcommSocketToServiceRecord(SPP);
                nuevo.connect();
            } catch (IOException ex) {
                cerrar(nuevo);
                runOnUiThread(() -> {
                    botonConectar.setEnabled(true);
                    mostrarEstado("No se pudo conectar", false);
                });
                return;
            }
            final BluetoothSocket listo = nuevo;
            runOnUiThread(() -> {
                if (isDestroyed()) {
                    cerrar(listo);
                    return;
                }
                socket = listo;
                presionados.clear();
                botonConectar.setEnabled(true);
                mostrarEstado("Conectado a " + equipo.getName(), true);
                enviar(DETENER);
                vigilar(listo);
            });
        }).start();
    }

    // Lee del socket para detectar cuando el robot se desconecta
    private void vigilar(BluetoothSocket s) {
        new Thread(() -> {
            try {
                InputStream entrada = s.getInputStream();
                byte[] buffer = new byte[64];
                while (entrada.read(buffer) >= 0) {
                    // El ESP32 no envía datos; se ignoran
                }
            } catch (IOException ex) {
                // Conexión cerrada
            }
            perderConexion(s);
        }).start();
    }

    private void perderConexion(BluetoothSocket s) {
        runOnUiThread(() -> {
            if (socket == s) desconectar("Se perdió la conexión");
        });
    }

    private void desconectar(String mensaje) {
        final BluetoothSocket s = socket;
        if (s == null) return;
        enviar(DETENER);
        socket = null;
        presionados.clear();
        envios.execute(() -> cerrar(s));
        mostrarEstado(mensaje, false);
    }

    private static void cerrar(BluetoothSocket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ex) {
            // Nada que hacer
        }
    }

    private void mostrarEstado(String texto, boolean conectado) {
        estado.setText(texto);
        estado.setTextColor(conectado ? Color.GREEN : Color.LTGRAY);
        botonConectar.setText(conectado ? "Desconectar" : "Conectar");
    }

    // Si la app pasa a segundo plano, el robot se detiene
    @Override
    protected void onPause() {
        presionados.clear();
        enviar(DETENER);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        desconectar("Desconectado");
        envios.shutdown();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams peso(int ancho, int alto, float peso) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ancho, alto, peso);
        p.setMargins(dp(6), dp(6), dp(6), dp(6));
        return p;
    }

    private int dp(int valor) {
        return Math.round(valor * getResources().getDisplayMetrics().density);
    }

    private void aviso(String texto) {
        Toast.makeText(this, texto, Toast.LENGTH_LONG).show();
    }
}
