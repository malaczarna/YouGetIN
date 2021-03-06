package pl.jarosyjarosy.yougetin.user.service;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import pl.jarosyjarosy.yougetin.destination.service.DestinationMapperService;
import pl.jarosyjarosy.yougetin.destination.service.DestinationService;
import pl.jarosyjarosy.yougetin.user.endpoint.message.RoleMessage;
import pl.jarosyjarosy.yougetin.user.endpoint.message.UserMessage;
import pl.jarosyjarosy.yougetin.user.model.Profile;
import pl.jarosyjarosy.yougetin.user.model.Role;
import pl.jarosyjarosy.yougetin.user.model.RoleType;
import pl.jarosyjarosy.yougetin.user.model.User;

import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class UserMapperService {
    private PasswordService passwordService;
    private DestinationMapperService destinationMapperService;
    private DestinationService destinationService;

    @Autowired
    public UserMapperService(PasswordService passwordService,
                             DestinationMapperService destinationMapperService,
                             DestinationService destinationService) {
        this.passwordService = passwordService;
        this.destinationMapperService = destinationMapperService;
        this.destinationService = destinationService;
    }

    public User mapUserMessage(UserMessage userMessage) throws InvalidKeySpecException, NoSuchAlgorithmException {
        User user = new User();
        user.setId(userMessage.getId());
        user.setPassword(passwordService.getPasswordHash(userMessage.getPassword()));
        user.setEmail(userMessage.getEmail());
        user.setName(userMessage.getName());
        user.setCarBrand(userMessage.getCarBrand());
        user.setCarColor(userMessage.getCarColor());
        user.setCarModel(userMessage.getCarModel());
        user.setPhoneNumber(userMessage.getPhoneNumber());
        if (userMessage.getRoles().stream().anyMatch(roleMessage -> roleMessage.getType().equals(RoleType.DRIVER))) {
            user.setCurrentProfile(Profile.DRIVER);
        } else {
            user.setCurrentProfile(Profile.PASSENGER);
        }

        return user;
    }

    public UserMessage mapUser(User user) {
        UserMessage userMessage = new UserMessage();
        userMessage.setId(user.getId());
        userMessage.setEmail(user.getEmail());
        userMessage.setName(user.getName());
        userMessage.setCurrentProfile(user.getCurrentProfile());
        userMessage.setLat(user.getLat());
        userMessage.setLng(user.getLng());
        userMessage.setCarBrand(user.getCarBrand());
        userMessage.setCarColor(user.getCarColor());
        userMessage.setCarModel(user.getCarModel());
        userMessage.setPhoneNumber(user.getPhoneNumber());
        if (user.getDestinationId() != null && user.getDestinationId() > 0) {
            userMessage.setDestination(destinationMapperService.mapDestination(destinationService.get(user.getDestinationId())));
        }
        return userMessage;
    }

    public RoleMessage mapRole(Role role) {
        RoleMessage roleMessage = new RoleMessage();
        BeanUtils.copyProperties(role, roleMessage);

        return roleMessage;
    }

     public Role mapRoleMessage(RoleMessage roleMessage) {
        Role role = new Role();
        BeanUtils.copyProperties(roleMessage, role);

        return role;
    }

    private List<RoleMessage> mapRoles(List<Role> roles) {
        return roles.stream().map(this::mapRole).collect(Collectors.toList());
    }

    public List<Role> mapRoles(UserMessage user) {
        if (user.getRoles() == null) {
            return Collections.emptyList();
        }
        return user.getRoles().stream().map(this::mapRoleMessage).collect(Collectors.toList());
    }
}