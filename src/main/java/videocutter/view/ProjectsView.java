package videocutter.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.ArrayList;
import java.util.List;

public class ProjectsView {
    private final BorderPane root = new BorderPane();

    private final TextField search = new TextField();
    private final ToggleGroup filterGroup = new ToggleGroup();
    private final ToggleButton all = pill("All", true);
    private final ToggleButton drafts = pill("Drafts", false);
    private final ToggleButton editing = pill("Editing", false);
    private final ToggleButton done = pill("Done", false);

    private final Button newProject = new Button("+  New Project");
    private final TilePane grid = new TilePane();

    private Runnable onNewProject;

    public ProjectsView() {
        root.getStyleClass().addAll("app-root", "projects-root");

        HBox brandRow = new HBox(12);
        brandRow.setAlignment(Pos.CENTER_LEFT);
        brandRow.setPadding(new Insets(28, 28, 12, 28));

        Label logo = new Label("FrameCut");
        logo.getStyleClass().add("brand-title");
        Label subtitle = new Label("Create stunning videos with ease");
        subtitle.getStyleClass().add("brand-subtitle");

        VBox brand = new VBox(4, logo, subtitle);
        brandRow.getChildren().add(brand);

        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.setPadding(new Insets(0, 28, 18, 28));

        search.setPromptText("Search projects...");
        search.getStyleClass().add("search");
        search.setPrefWidth(320);

        HBox pills = new HBox(8, all, drafts, editing, done);
        pills.getStyleClass().add("pill-row");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        newProject.getStyleClass().addAll("btn", "btn-primary");
        newProject.setOnAction(e -> { if (onNewProject != null) onNewProject.run(); });

        controls.getChildren().addAll(search, pills, spacer, newProject);

        root.setTop(new VBox(brandRow, controls));

        grid.setPadding(new Insets(8, 28, 28, 28));
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setPrefColumns(4);
        grid.getStyleClass().add("card-grid");

        setCards(List.of());

        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("transparent-scroll");
        root.setCenter(scroll);
    }

    public void setOnNewProject(Runnable r) { this.onNewProject = r; }

    public BorderPane getRoot() { return root; }

    public record ProjectCardModel(String title, String status, String metaLeft, String metaRight, String badge) {}

    public void setCards(List<ProjectCardModel> cards) {
        grid.getChildren().clear();
        for (ProjectCardModel m : cards) grid.getChildren().add(new ProjectCard(m));
    }

    private ToggleButton pill(String text, boolean selected) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(filterGroup);
        b.setSelected(selected);
        b.getStyleClass().add("pill");
        return b;
    }

    private static List<ProjectCardModel> sampleCards() {
        List<ProjectCardModel> out = new ArrayList<>();
        out.add(new ProjectCardModel("Summer Travel Vlog", "editing", "1080p • 16:9", "Feb 10, 2026", "3:05"));
        out.add(new ProjectCardModel("Product Launch Promo", "completed", "4K • 16:9", "Feb 10, 2026", "1:00"));
        out.add(new ProjectCardModel("Instagram Reel - Recipe", "draft", "1080p • 9:16", "Feb 10, 2026", "0:45"));
        out.add(new ProjectCardModel("Podcast Episode 12", "editing", "720p • 16:9", "Feb 10, 2026", "53:20"));
        return out;
    }

    static class ProjectCard extends VBox {
        ProjectCard(ProjectCardModel m) {
            getStyleClass().add("project-card");
            setPrefWidth(300);
            setMinWidth(260);

            StackPane thumb = new StackPane();
            thumb.getStyleClass().add("card-thumb");
            thumb.setPrefHeight(168);

            Label badge = new Label(m.badge());
            badge.getStyleClass().add("duration-badge");
            StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(badge, new Insets(0, 10, 10, 0));
            thumb.getChildren().add(badge);

            Label title = new Label(m.title());
            title.getStyleClass().add("card-title");

            Label date = new Label("●  " + m.metaRight());
            date.getStyleClass().add("card-date");

            Label status = new Label(m.status());
            status.getStyleClass().addAll("status", "status-" + m.status());

            HBox titleRow = new HBox(10, title, new Region(), status);
            HBox.setHgrow(titleRow.getChildren().get(1), Priority.ALWAYS);
            titleRow.setAlignment(Pos.CENTER_LEFT);

            Label meta = new Label(m.metaLeft());
            meta.getStyleClass().add("card-meta");

            VBox body = new VBox(6, titleRow, date, meta);
            body.setPadding(new Insets(12));
            body.getStyleClass().add("card-body");

            getChildren().addAll(thumb, body);
        }
    }
}
