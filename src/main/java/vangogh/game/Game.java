package vangogh.game;

public record Game(
        int id,
        String game_title,
        String release_date,
        double rating,
        String genre,
        String overview,
        String posterUrl   
) {
}

//public record Game(
//	    int id,
//	    String game_title,
//	    String release_date,
//	    int platform,
//	    int players,
//	    String overview,
//	    String rating,
//	    List<Integer> developers,
//	    List<Integer> genres,
//	    List<Integer> publishers
//	) {}