import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.scene.Scene;
import javafx.scene.chart.PieChart;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Properties;

public class ExpenseTrackerMultiCurrency extends Application {
    private final ObservableList<Expense> expenseData = FXCollections.observableArrayList();
    private final ObservableList<String> categoryList = FXCollections.observableArrayList("Food", "Travel", "Bills", "Shopping", "Other");
    private final ObservableList<String> currencyList = FXCollections.observableArrayList("USD", "EUR", "GBP", "INR", "JPY", "AUD");

    private String baseCurrency = "USD";

    private TableView<Expense> expenseTable;
    private Label totalLabel;
    private PieChart categoryChart;
    private ComboBox<String> baseCurrencyCombo;

    // formatting
    private final NumberFormat nf2 = NumberFormat.getNumberInstance();

    // simple config
    private final Properties props = new Properties();
    private Path configPath;

    public static void main(String[] args) { launch(args); }

    @Override
    public void start(Stage stage) {
        // config
        configPath = Path.of(System.getProperty("user.home"), ".expense_tracker.properties");
        loadConfig();

        nf2.setMinimumFractionDigits(2); nf2.setMaximumFractionDigits(2);

        VBox root = new VBox(10);
        root.setPadding(new Insets(12));

        // --- Inputs in a ToolBar with a compact GridPane ---
        DatePicker datePicker = new DatePicker(LocalDate.now());
        datePicker.setTooltip(new Tooltip("Date of the expense"));

        ComboBox<String> categoryBox = new ComboBox<>(categoryList);
        setComboPrompt(categoryBox, "Category");
        categoryBox.setTooltip(new Tooltip("Pick a category"));

        ComboBox<String> currencyBox = new ComboBox<>(currencyList);
        setComboPrompt(currencyBox, "Currency");
        currencyBox.setTooltip(new Tooltip("Currency of the amount"));

        TextField amountField = new TextField();
        amountField.setPromptText("Amount");
        amountField.setTooltip(new Tooltip("Numeric amount (> 0)"));

        TextField descField = new TextField();
        descField.setPromptText("Description");
        descField.setTooltip(new Tooltip("Short description"));

        Button addBtn = new Button("Add");
        addBtn.setDefaultButton(true);
        addBtn.setTooltip(new Tooltip("Add expense (Enter)"));

        Button deleteBtn = new Button("Delete Selected");
        deleteBtn.setTooltip(new Tooltip("Delete selected row(s)"));

        // disable Add until valid
        addBtn.disableProperty().bind(
            amountField.textProperty().isEmpty()
                .or(categoryBox.valueProperty().isNull())
                .or(currencyBox.valueProperty().isNull())
                .or(descField.textProperty().isEmpty())
        );

        GridPane form = new GridPane();
        form.setHgap(8); form.setVgap(6);
        form.addRow(0, datePicker, categoryBox, currencyBox, amountField, descField, addBtn, deleteBtn);

        ToolBar bar = new ToolBar(form);

        // --- Table ---
        expenseTable = new TableView<>(expenseData);
        expenseTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        expenseTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        TableColumn<Expense, LocalDate> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(new PropertyValueFactory<>("date"));

        TableColumn<Expense, String> catCol = new TableColumn<>("Category");
        catCol.setCellValueFactory(new PropertyValueFactory<>("category"));

        TableColumn<Expense, String> curCol = new TableColumn<>("Currency");
        curCol.setCellValueFactory(new PropertyValueFactory<>("currency"));

        TableColumn<Expense, Double> amountCol = new TableColumn<>("Amount");
        amountCol.setCellValueFactory(new PropertyValueFactory<>("amount"));
        amountCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        amountCol.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Double v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? "" : nf2.format(v));
            }
        });

        TableColumn<Expense, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(new PropertyValueFactory<>("description"));

        expenseTable.getColumns().addAll(dateCol, catCol, curCol, amountCol, descCol);
        expenseTable.setPrefHeight(260);

        // context menu delete
        ContextMenu ctx = new ContextMenu();
        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(e -> deleteSelected());
        ctx.getItems().add(deleteItem);
        expenseTable.setContextMenu(ctx);

        // --- Totals & chart ---
        totalLabel = new Label();
        totalLabel.getStyleClass().add("total-badge");

        categoryChart = new PieChart();
        categoryChart.setTitle("Spending by Category");
        categoryChart.setLegendSide(Side.RIGHT);

        // --- Settings row ---
        HBox settings = new HBox(10);
        baseCurrencyCombo = new ComboBox<>(currencyList);
        baseCurrencyCombo.setValue(baseCurrency); // from config if available
        baseCurrencyCombo.setOnAction(e -> {
            baseCurrency = baseCurrencyCombo.getValue();
            recomputeAllBaseAmounts();
            updateTotal();
            updatePieChart();
        });
        settings.getChildren().addAll(new Label("Base Currency:"), baseCurrencyCombo);

        // --- Event handlers ---
        addBtn.setOnAction(e -> {
            try {
                LocalDate date = datePicker.getValue();
                String cat = categoryBox.getValue();
                String cur = currencyBox.getValue();
                String desc = descField.getText();
                if (date == null || cat == null || cur == null || desc == null || desc.isEmpty()) {
                    showAlert("Please fill all fields."); return;
                }
                double amt = Double.parseDouble(amountField.getText());
                if (amt <= 0) { showAlert("Amount must be > 0."); return; }

                double baseAmt = amt * getExchangeRate(cur, baseCurrency);
                expenseData.add(new Expense(date, cat, cur, amt, baseAmt, desc));
                updateTotal(); updatePieChart();

                // reset inputs
                datePicker.setValue(LocalDate.now());
                categoryBox.getSelectionModel().clearSelection();
                currencyBox.getSelectionModel().clearSelection();
                amountField.clear(); descField.clear();
            } catch (NumberFormatException ex) {
                showAlert("Amount must be a valid number.");
            }
        });

        deleteBtn.setOnAction(e -> deleteSelected());

        // --- Layout build ---
        root.getChildren().addAll(bar, settings, expenseTable, totalLabel, categoryChart);

        // --- Scene + CSS ---
        Scene scene = new Scene(root, readDouble("win.width", 900), readDouble("win.height", 640));
        // try classpath resource first, then ./app.css
        try {
            var res = getClass().getResource("app.css");
            if (res != null) scene.getStylesheets().add(res.toExternalForm());
            else if (Files.exists(Path.of("app.css"))) {
                scene.getStylesheets().add(Path.of("app.css").toUri().toString());
            }
        } catch (Exception ignored) {}

        stage.setScene(scene);
        stage.setTitle("Multi-Currency Expense Tracker");
        stage.show();

        // initial totals
        updateTotal(); updatePieChart();

        // save config on exit
        stage.setOnCloseRequest(e -> saveConfig(stage));
        Platform.runLater(() -> { // after first layout, persist baseline size
            stage.setWidth(stage.getWidth());
            stage.setHeight(stage.getHeight());
        });
    }

    // ----- actions -----
    private void deleteSelected() {
        var selected = FXCollections.observableArrayList(expenseTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) { showAlert("Select at least one row to delete."); return; }
        expenseData.removeAll(selected);
        updateTotal(); updatePieChart();
    }

    private void recomputeAllBaseAmounts() {
        for (Expense e : expenseData) {
            double rate = getExchangeRate(e.getCurrency(), baseCurrency);
            e.setBaseAmount(e.getAmount() * rate);
        }
    }

    private void updateTotal() {
        double total = expenseData.stream().mapToDouble(Expense::getBaseAmount).sum();
        totalLabel.setText(String.format("Total: %s %s", nf2.format(total), baseCurrency));
    }

    private void updatePieChart() {
        categoryChart.getData().clear();
        for (String cat : categoryList) {
            double sum = expenseData.stream()
                    .filter(e -> e.getCategory().equals(cat))
                    .mapToDouble(Expense::getBaseAmount)
                    .sum();
            if (sum > 0) categoryChart.getData().add(new PieChart.Data(cat, sum));
        }
    }

    // ----- fixed demo rates + simple crosses if needed -----
    private double getExchangeRate(String from, String to) {
        if (from.equals(to)) return 1.0;

        // direct pairs
        if (from.equals("USD") && to.equals("INR")) return 82.0;
        if (from.equals("INR") && to.equals("USD")) return 1/82.0;

        if (from.equals("USD") && to.equals("EUR")) return 0.93;
        if (from.equals("EUR") && to.equals("USD")) return 1/0.93;

        if (from.equals("USD") && to.equals("GBP")) return 0.79;
        if (from.equals("GBP") && to.equals("USD")) return 1/0.79;

        if (from.equals("USD") && to.equals("JPY")) return 151.0;
        if (from.equals("JPY") && to.equals("USD")) return 1/151.0;

        if (from.equals("USD") && to.equals("AUD")) return 1.49;
        if (from.equals("AUD") && to.equals("USD")) return 1/1.49;

        // cross via USD
        double usdToFrom = getExchangeRate("USD", from); // e.g., USD->INR
        double usdToTo   = getExchangeRate("USD", to);   // e.g., USD->EUR
        if (usdToFrom == 0) return 1.0;
        return usdToTo / usdToFrom;
    }

    private static <T> void setComboPrompt(ComboBox<T> combo, String prompt) {
        combo.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText((empty || item == null) ? prompt : String.valueOf(item));
            }
        });
        combo.setPromptText(prompt);
    }

    // ----- model -----
    public static class Expense {
        private LocalDate date;
        private String category, currency, description;
        private double amount, baseAmount;

        public Expense(LocalDate d, String c, String cur, double a, double b, String desc) {
            date = d; category = c; currency = cur; amount = a; baseAmount = b; description = desc;
        }

        public LocalDate getDate() { return date; }
        public String getCategory() { return category; }
        public String getCurrency() { return currency; }
        public double getAmount() { return amount; }
        public double getBaseAmount() { return baseAmount; }
        public String getDescription() { return description; }
        public void setBaseAmount(double b) { baseAmount = b; }
    }

    // ----- alerts -----
    private void showAlert(String msg) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    // ----- config I/O -----
    private void loadConfig() {
        // defaults
        props.setProperty("baseCurrency", "USD");
        props.setProperty("win.width", "900");
        props.setProperty("win.height", "640");

        try (InputStream in = Files.exists(configPath) ? Files.newInputStream(configPath) : null) {
            if (in != null) props.load(in);
        } catch (IOException ignored) {}

        baseCurrency = props.getProperty("baseCurrency", "USD");
    }

    private void saveConfig(Stage stage) {
        props.setProperty("baseCurrency", Objects.toString(baseCurrencyCombo.getValue(), "USD"));
        props.setProperty("win.width", Double.toString(stage.getWidth()));
        props.setProperty("win.height", Double.toString(stage.getHeight()));
        try (OutputStream out = Files.newOutputStream(configPath)) {
            props.store(out, "Expense Tracker settings");
        } catch (IOException ignored) {}
    }

    private double readDouble(String key, double def) {
        try { return Double.parseDouble(props.getProperty(key, Double.toString(def))); }
        catch (Exception e) { return def; }
    }
}
