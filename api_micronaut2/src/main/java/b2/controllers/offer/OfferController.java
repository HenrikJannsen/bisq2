package b2.controllers.offer;

import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;

import java.util.ArrayList;
import java.util.List;

@Controller("/trade/offers")
public class OfferController {

    private List<Offer> offers = new ArrayList<>();

    @Get
    public List<Offer> list() {
        return offers;
    }

    @Post
    public void create(Offer offer) {
        System.out.println("OfferController.create: " + offer);
        offers.add(offer);
    }
}

