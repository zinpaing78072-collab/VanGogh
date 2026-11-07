package vangogh.movie;

public record Movie(
        int id,
        String title,
        int year,
        double rating,
        String genre,
        String overview,
        String posterUrl
) {
}