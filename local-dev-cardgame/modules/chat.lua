---- chat.lua
---- Server-side chat functionality for Nakama
--
--local nk = require("nakama")
--
---- Hook to be called when a user joins a chat channel
--local function on_channel_join(context, payload)
--  local channel_id = payload.channel_id
--  local user_id = context.user_id
--  local username = context.username
--
--  nk.logger_info(string.format("User %s (%s) joined channel %s", username, user_id, channel_id))
--
--  -- Optional: Send a system message to the channel
--  nk.channel_message_send(channel_id, {
--    type = "system",
--    content = json.encode({
--      text = username .. " has joined the channel",
--      sender = "System"
--    })
--  })
--
--  return payload
--end
--
---- Hook to be called when a user leaves a chat channel
--local function on_channel_leave(context, payload)
--  local channel_id = payload.channel_id
--  local user_id = context.user_id
--  local username = context.username
--
--  nk.logger_info(string.format("User %s (%s) left channel %s", username, user_id, channel_id))
--
--  -- Optional: Send a system message to the channel
--  nk.channel_message_send(channel_id, {
--    type = "system",
--    content = json.encode({
--      text = username .. " has left the channel",
--      sender = "System"
--    })
--  })
--
--  return payload
--end
--
---- Hook to process messages before they're sent
--local function on_channel_message(context, payload)
--  local message = payload.content
--
--  -- Basic profanity filter example
--  local profanity = {
--    "badword1",
--    "badword2"
--  }
--
--  for _, word in ipairs(profanity) do
--    message = string.gsub(message, word, "****")
--  end
--
--  payload.content = message
--
--  -- Log message for debugging
--  nk.logger_info(string.format("Message in channel %s: %s", payload.channel_id, message))
--
--  return payload
--end
--
---- Hook for notifications/presence events
--local function on_user_presence(context, payload)
--  local presence = payload.joins[1]
--  if presence then
--    nk.logger_info(string.format("User presence: %s (%s)", presence.username, presence.user_id))
--  end
--  return payload
--end
--
---- Register hooks with the correct function names
--nk.register_before_rt("ChannelJoin", on_channel_join)
--nk.register_before_rt("ChannelLeave", on_channel_leave)
--nk.register_before_rt("ChannelMessage", on_channel_message)
--nk.register_after_presence_event(on_user_presence)
