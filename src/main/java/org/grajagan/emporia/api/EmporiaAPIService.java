package org.grajagan.emporia.api;

/*-
 * #%L
 * Emporia Energy API Client
 * %%
 * Copyright (C) 2002 - 2020 Helge Weissig
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import lombok.extern.log4j.Log4j2;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.configuration.Configuration;
import org.grajagan.aws.CognitoAuthenticationManager;
import org.grajagan.emporia.JacksonObjectMapper;
import org.grajagan.emporia.model.Channel;
import org.grajagan.emporia.model.Customer;
import org.grajagan.emporia.model.Readings;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

import java.io.IOException;
import java.time.Instant;

@Log4j2
public class EmporiaAPIService {
    public static final String API_URL = "https://api.emporiaenergy.com";

    public static final String USERNAME = "username";
    public static final String PASSWORD = "password";
    public static final String CLIENTAPP_ID = "clientapp-id";
    public static final String POOL_ID = "pool-id";
    public static final String REGION = "region";
    public static final String MAINTENANCE_URL = "http://s3.amazonaws.com/"
            + "com.emporiaenergy.manual.ota/maintenance/maintenance.json";

    private final EmporiaAPI emporiaAPI;
    private final String username;
    private final OkHttpClient simpleClient;

    public EmporiaAPIService(Configuration configuration) {
        CognitoAuthenticationManager authenticationManager =
                CognitoAuthenticationManager.builder().username(configuration.getString(USERNAME))
                        .password(configuration.getString(PASSWORD))
                        .poolId(configuration.getString(POOL_ID))
                        .region(configuration.getString(REGION))
                        .clientId(configuration.getString(CLIENTAPP_ID)).build();

        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.addInterceptor(new EmporiaAPIInterceptor(authenticationManager));

        OkHttpClient client = builder.build();
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(API_URL)
                .addConverterFactory(JacksonConverterFactory.create(new JacksonObjectMapper()))
                .client(client)
                .build();

        emporiaAPI = retrofit.create(EmporiaAPI.class);
        username = configuration.getString(USERNAME);
        simpleClient = new OkHttpClient.Builder().build();
    }

    public boolean isDownForMaintenance() {
        Request request = new Request.Builder().url(MAINTENANCE_URL).build();
        try {
            Response response = simpleClient.newCall(request).execute();
            return response.isSuccessful();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public Customer getCustomer() {
        Call<Customer> customerCall = emporiaAPI.getCustomer(username);
        Customer customer = null;
        try {
            customer = customerCall.execute().body();
            customerCall = emporiaAPI.getCustomer(customer.getCustomerGid());
            customer = customerCall.execute().body();
        } catch (IOException e) {
            log.error("Cannot get customer!", e);
        }

        return customer;
    }

    public Readings getReadings(Channel channel, Instant start, Instant end) {
        Call<Readings> readingsCall = emporiaAPI.getReadings(start, end, Readings.DEFAULT_TYPE, channel.getDeviceGid(),
                Readings.DEFAULT_SCALE, Readings.DEFAULT_UNIT, channel.getChannelNum());
        Readings readings = null;
        try {
            readings = readingsCall.execute().body();
            readings.setChannel(channel);
        } catch (IOException e) {
            log.error("Cannot get readings!", e);
        }
        return readings;
    }
}
