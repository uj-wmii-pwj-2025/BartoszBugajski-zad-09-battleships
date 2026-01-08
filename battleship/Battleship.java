import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

// Użycie: java Battleship -mode [server|client] -port N -map plik -host hostName
public class Battleship {
    private static final int TIMEOUT = 60000;
    private static final int MAX_RETRIES = 3;
    private static final Scanner scanner = new Scanner(System.in);

    public static void main(String[] args) {
        Map<String, String> arguments = parseArgs(args);

        String mode = arguments.get("-mode");
        int port = Integer.parseInt(arguments.getOrDefault("-port", "0"));
        String mapFile = arguments.get("-map");
        String host = arguments.getOrDefault("-host", "localhost");

        if (mode == null || mapFile == null) {
            System.out.println("Użycie: java Battleship -mode [server|client] -port N -map plik -host hostName");
            return;
        }

        try {
            String mapString = loadOrGenerateMap(mapFile);
            GameLogic game = new GameLogic(mapString);

            System.out.println("=== GRA W OKRĘTY ===");
            System.out.println("Legenda mapy przeciwnika: '.' - nieznane, '~' - pudło, '#' - trafiony");

            try (Socket socket = establishConnection(mode, host, port);
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                 BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {

                socket.setSoTimeout(TIMEOUT);

                if ("server".equalsIgnoreCase(mode)) {
                    runServerLoop(game, in, out);
                } else {
                    runClientLoop(game, in, out);
                }

            } catch (IOException e) {
                System.out.println("Błąd połączenia: " + e.getMessage());
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void runClientLoop(GameLogic game, BufferedReader in, PrintWriter out) throws IOException {
        game.printBothMaps();
        String target = getUserInput();
        String lastMsgSent = "start;" + target;

        sendMessage(out, lastMsgSent);
        game.setLastTarget(target);

        while (true) {
            System.out.println("Czekanie na ruch przeciwnika...");
            String received = receiveWithRetry(in, out, lastMsgSent);
            if (received == null) break;

            if (received.trim().equals("ostatni zatopiony")) {
                handleWin(game);
                break;
            }

            String[] parts = received.split(";");
            String myShotResult = parts[0];
            String enemyShotCoords = parts[1];

            System.out.println("\n--- TURA ---\n");

            System.out.println("Wynik Twojego strzału w " + game.getLastTarget() + ": " + myShotResult.toUpperCase());
            game.updateEnemyMap(myShotResult);

            String resultForEnemy = game.processEnemyShot(enemyShotCoords);
            System.out.println("Przeciwnik strzelił w " + enemyShotCoords + ". Twój wynik: " + resultForEnemy.toUpperCase());

            if (resultForEnemy.equals("ostatni zatopiony")) {
                sendMessage(out, resultForEnemy);
                handleLoss(game);
                break;
            }

            game.printBothMaps();
            String nextTarget = getUserInput();

            lastMsgSent = resultForEnemy + ";" + nextTarget;
            sendMessage(out, lastMsgSent);
            game.setLastTarget(nextTarget);
        }
    }

    private static void runServerLoop(GameLogic game, BufferedReader in, PrintWriter out) throws IOException {
        String lastMsgSent = null;

        while (true) {
            System.out.println("Czekanie na ruch przeciwnika...");
            String received = receiveWithRetry(in, out, lastMsgSent);
            if (received == null) break;

            if (received.trim().equals("ostatni zatopiony")) {
                handleWin(game);
                break;
            }

            String[] parts = received.split(";");
            String command = parts[0];
            String enemyShotCoords = parts[1];

            System.out.println("\n--- TURA ---\n");

            if (!command.equals("start")) {
                System.out.println("Wynik Twojego strzału w " + game.getLastTarget() + ": " + command.toUpperCase());
                game.updateEnemyMap(command);
            } else {
                System.out.println("Przeciwnik rozpoczyna grę.");
            }

            String resultForEnemy = game.processEnemyShot(enemyShotCoords);
            System.out.println("Przeciwnik strzelił w " + enemyShotCoords + ". Twój wynik: " + resultForEnemy.toUpperCase());

            if (resultForEnemy.equals("ostatni zatopiony")) {
                sendMessage(out, resultForEnemy);
                handleLoss(game);
                break;
            }

            game.printBothMaps();
            String nextTarget = getUserInput();

            lastMsgSent = resultForEnemy + ";" + nextTarget;
            sendMessage(out, lastMsgSent);
            game.setLastTarget(nextTarget);
        }
    }

    private static String getUserInput() {
        while (true) {
            System.out.print("Twój ruch (np. A5): ");
            String input = scanner.nextLine().trim().toUpperCase();

            if (isValidCoord(input)) {
                return input;
            }
            System.out.println("Niepoprawny format! Użyj litery A-J i liczby 1-10 (np. A1, J10).");
        }
    }

    private static boolean isValidCoord(String coords) {
        if (coords.length() < 2 || coords.length() > 3) return false;
        char row = coords.charAt(0);
        if (row < 'A' || row > 'J') return false;
        try {
            int col = Integer.parseInt(coords.substring(1));
            return col >= 1 && col <= 10;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static void sendMessage(PrintWriter out, String msg) {
        out.print(msg + "\n");
        out.flush();
    }

    private static String receiveWithRetry(BufferedReader in, PrintWriter out, String lastMsgSent) {
        int attempts = 0;
        while (attempts < MAX_RETRIES) {
            try {
                String line = in.readLine();
                if (line == null) throw new IOException("Połączenie zamknięte");
                return line;
            } catch (IOException e) {
                attempts++;
                System.out.println("Błąd/Timeout (" + attempts + "/" + MAX_RETRIES + "): " + e.getMessage());
                if (attempts >= MAX_RETRIES) {
                    System.out.println("Błąd komunikacji");
                    return null;
                }
                if (lastMsgSent != null) {
                    System.out.println("Ponawianie wysyłania...");
                    sendMessage(out, lastMsgSent);
                }
            }
        }
        return null;
    }

    private static Socket establishConnection(String mode, String host, int port) throws IOException {
        if ("server".equalsIgnoreCase(mode)) {
            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("Serwer nasłuchuje na porcie " + port + ". Oczekiwanie na gracza...");
            Socket socket = serverSocket.accept();
            serverSocket.close();
            System.out.println("Gracz połączony!");
            return socket;
        } else {
            System.out.println("Łączenie z " + host + ":" + port + "...");
            return new Socket(host, port);
        }
    }

    private static void handleWin(GameLogic game) {
        System.out.println("\nWYGRANA");
        game.printFinalEnemyMap(true);
    }

    private static void handleLoss(GameLogic game) {
        System.out.println("\nPRZEGRANA");
        game.printFinalEnemyMap(false);
        game.printMyFinalMap();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < args.length - 1; i += 2) {
            map.put(args[i], args[i + 1]);
        }
        return map;
    }

    private static String loadOrGenerateMap(String path) {
        try {
            if (Files.exists(Paths.get(path))) {
                String content = new String(Files.readAllBytes(Paths.get(path))).trim();
                if (content.length() == 100) return content;
            }
        } catch (IOException ignored) {}
        return BattleshipGenerator.defaultInstance().generateMap();
    }
}

class GameLogic {
    private final char[][] myBoard = new char[10][10];
    private final boolean[][] myHits = new boolean[10][10];
    private final char[][] enemyBoard = new char[10][10];
    private final List<Set<Integer>> myShips = new ArrayList<>();
    private int totalMySegmentsHit = 0;
    private final int TOTAL_SEGMENTS = 20;
    private String lastTargetCoords = null;

    public GameLogic(String mapString) {
        for (int i = 0; i < 100; i++) {
            int r = i / 10;
            int c = i % 10;
            myBoard[r][c] = mapString.charAt(i);
            enemyBoard[r][c] = '.';
        }
        detectShips();
    }

    public void setLastTarget(String target) {
        this.lastTargetCoords = target;
    }

    public String getLastTarget() {
        return this.lastTargetCoords;
    }

    private void detectShips() {
        boolean[][] visited = new boolean[10][10];
        for (int r = 0; r < 10; r++) {
            for (int c = 0; c < 10; c++) {
                if (myBoard[r][c] == '#' && !visited[r][c]) {
                    Set<Integer> ship = new HashSet<>();
                    floodFill(r, c, visited, ship);
                    myShips.add(ship);
                }
            }
        }
    }

    private void floodFill(int r, int c, boolean[][] visited, Set<Integer> ship) {
        if (r < 0 || r >= 10 || c < 0 || c >= 10) return;
        if (visited[r][c] || myBoard[r][c] != '#') return;
        visited[r][c] = true;
        ship.add(r * 10 + c);
        floodFill(r + 1, c, visited, ship);
        floodFill(r - 1, c, visited, ship);
        floodFill(r, c + 1, visited, ship);
        floodFill(r, c - 1, visited, ship);
    }

    public String processEnemyShot(String coords) {
        Point p = parseCoords(coords);
        if (p == null) return "pudło";

        boolean alreadyHit = myHits[p.row][p.col];
        myHits[p.row][p.col] = true;

        if (myBoard[p.row][p.col] != '#') {
            return "pudło";
        }

        if (!alreadyHit) {
            totalMySegmentsHit++;
        }

        if (totalMySegmentsHit == TOTAL_SEGMENTS) {
            return "ostatni zatopiony";
        }

        for (Set<Integer> ship : myShips) {
            if (ship.contains(p.row * 10 + p.col)) {
                boolean sunk = true;
                for (int cell : ship) {
                    int r = cell / 10;
                    int c = cell % 10;
                    if (!myHits[r][c]) {
                        sunk = false;
                        break;
                    }
                }
                if (sunk) return "trafiony zatopiony";
            }
        }
        return "trafiony";
    }

    public void updateEnemyMap(String result) {
        if (lastTargetCoords == null) return;
        Point p = parseCoords(lastTargetCoords);
        if (p == null) return;

        if (result.startsWith("pudło")) {
            enemyBoard[p.row][p.col] = '~';
        } else if (result.startsWith("trafiony")) {
            enemyBoard[p.row][p.col] = '#';
            if (result.contains("zatopiony")) {
                markSunkShipOnEnemyMap(p.row, p.col);
            }
        }
    }

    private void markSunkShipOnEnemyMap(int r, int c) {
        Queue<Point> q = new LinkedList<>();
        Set<Integer> shipParts = new HashSet<>();
        q.add(new Point(r, c));
        shipParts.add(r * 10 + c);

        while(!q.isEmpty()) {
            Point curr = q.poll();
            int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1}};
            for(int[] d : dirs) {
                int nr = curr.row + d[0];
                int nc = curr.col + d[1];
                if(nr >= 0 && nr < 10 && nc >= 0 && nc < 10) {
                    if(enemyBoard[nr][nc] == '#' && !shipParts.contains(nr*10+nc)) {
                        shipParts.add(nr*10+nc);
                        q.add(new Point(nr, nc));
                    }
                }
            }
        }
        for(int cell : shipParts) {
            int cr = cell / 10;
            int cc = cell % 10;
            for(int i=cr-1; i<=cr+1; i++) {
                for(int j=cc-1; j<=cc+1; j++) {
                    if(i>=0 && i<10 && j>=0 && j<10) {
                        if(enemyBoard[i][j] != '#') {
                            enemyBoard[i][j] = 'o';
                        }
                    }
                }
            }
        }
    }

    private Point parseCoords(String coords) {
        try {
            char rowChar = coords.charAt(0);
            int row = rowChar - 'A';
            int col = Integer.parseInt(coords.substring(1)) - 1;
            if (row >= 0 && row < 10 && col >= 0 && col < 10) return new Point(row, col);
        } catch (Exception e) {}
        return null;
    }

    public void printBothMaps() {
        System.out.println("   TWOJA MAPA               MAPA PRZECIWNIKA");
        System.out.println("   1 2 3 4 5 6 7 8 9 10     1 2 3 4 5 6 7 8 9 10");
        for (int r = 0; r < 10; r++) {
            System.out.print((char)('A' + r) + "  ");
            for (int c = 0; c < 10; c++) {
                char symbol;
                if (myBoard[r][c] == '#') symbol = myHits[r][c] ? '@' : '#';
                else symbol = myHits[r][c] ? '~' : '.';
                System.out.print(symbol + " ");
            }

            System.out.print("  ");

            System.out.print((char)('A' + r) + "  ");
            for (int c = 0; c < 10; c++) {
                char ch = enemyBoard[r][c];
                char symbol;
                if (ch == '#') symbol = '#';
                else if (ch == '~') symbol = '~';
                else if (ch == 'o') symbol = '.';
                else symbol = '.';
                System.out.print(symbol + " ");
            }
            System.out.println();
        }
        System.out.println();
    }

    public void printMyFinalMap() {
        System.out.println("Stan Twojej floty:");
        System.out.print("   ");
        for(int i=1;i<=10;i++) System.out.print(i + " ");
        System.out.println();
        for(int r=0; r<10; r++) {
            System.out.print((char)('A' + r) + "  ");
            for(int c=0; c<10; c++) {
                if (myBoard[r][c] == '#') System.out.print((myHits[r][c] ? '@' : '#') + " ");
                else System.out.print((myHits[r][c] ? '~' : '.') + " ");
            }
            System.out.println();
        }
    }

    public void printFinalEnemyMap(boolean won) {
        System.out.println("Mapa Przeciwnika (Koniec gry):");
        System.out.print("   ");
        for(int i=1;i<=10;i++) System.out.print(i + " ");
        System.out.println();
        for(int r=0; r<10; r++) {
            System.out.print((char)('A' + r) + "  ");
            for(int c=0; c<10; c++) {
                char ch = enemyBoard[r][c];
                if (won) {
                    System.out.print((ch == '#' ? '#' : '.') + " ");
                } else {
                    char symbol = '?';
                    if (ch == '#') symbol = '#';
                    else if (ch == '~' || ch == 'o') symbol = '.';
                    System.out.print(symbol + " ");
                }
            }
            System.out.println();
        }
    }

    private static class Point {
        int row, col;
        Point(int r, int c) { this.row = r; this.col = c; }
    }
}

interface BattleshipGenerator {
    String generateMap();
    static BattleshipGenerator defaultInstance() {
        return new BattleshipGeneratorImpl();
    }
}

class BattleshipGeneratorImpl implements BattleshipGenerator {
    private static final int SIZE = 10;
    private static final char WATER = '.';
    private static final char SHIP = '#';
    private static final char NO_PLACEMENT_ZONE = 'X';
    private char[][] map;
    private final Random rand = new Random();

    @Override
    public String generateMap() {
        map = new char[SIZE][SIZE];
        for(char[] row : map) Arrays.fill(row, WATER);
        int[] ships = new int[]{4, 3, 3, 2, 2, 2, 1, 1, 1, 1};
        for(int i : ships) placeShip(i);
        StringBuilder result = new StringBuilder();
        for(int i = 0; i < SIZE; i++) {
            for(int j = 0; j < SIZE; j++) {
                result.append(map[i][j] == SHIP ? SHIP : WATER);
            }
        }
        return result.toString();
    }

    private void placeShip(int length) {
        int row = rand.nextInt(SIZE);
        int col = rand.nextInt(SIZE);
        List<Character> directions = availableDirections(row, col, length);
        while(directions.isEmpty()) {
            row = rand.nextInt(SIZE);
            col = rand.nextInt(SIZE);
            directions = availableDirections(row, col, length);
        }
        int d = rand.nextInt(directions.size());
        placeShipInDirection(row, col, length, directions.get(d));
    }

    private void placeShipInDirection(int row, int col, int length, char direction) {
        while(length-- > 0) {
            map[row][col] = SHIP;
            setUnavailableSpots(row, col);
            switch(direction){
                case 'U': row--; break;
                case 'D': row++; break;
                case 'L': col--; break;
                case 'R': col++; break;
            }
        }
    }

    private void setUnavailableSpots(int row, int col) {
        for(int i = row-1; i <= row+1; i++) {
            for(int j = col-1; j <= col+1; j++) {
                if(i >= 0 && i < SIZE && j >= 0 && j < SIZE && map[i][j] != SHIP) {
                    map[i][j] = NO_PLACEMENT_ZONE;
                }
            }
        }
    }

    private List<Character> availableDirections(int row, int col, int length) {
        List<Character> directions = new ArrayList<>();
        directions.add('U'); directions.add('D'); directions.add('L'); directions.add('R');
        for(int i = 1; i < length; i++) {
            if(row - i < 0 || map[row-i][col] == NO_PLACEMENT_ZONE || map[row-i][col] == SHIP) directions.remove((Character)'U');
            if(row + i >= SIZE || map[row+i][col] == NO_PLACEMENT_ZONE || map[row+i][col] == SHIP) directions.remove((Character)'D');
            if(col - i < 0 || map[row][col-i] == NO_PLACEMENT_ZONE || map[row][col-i] == SHIP) directions.remove((Character)'L');
            if(col + i >= SIZE || map[row][col+i] == NO_PLACEMENT_ZONE || map[row][col+i] == SHIP) directions.remove((Character)'R');
        }
        return directions;
    }
}